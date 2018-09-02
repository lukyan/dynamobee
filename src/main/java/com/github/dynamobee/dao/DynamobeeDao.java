package com.github.dynamobee.dao;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.amazonaws.services.dynamodbv2.document.DynamoDB;
import com.amazonaws.services.dynamodbv2.document.Expected;
import com.amazonaws.services.dynamodbv2.document.Item;
import com.amazonaws.services.dynamodbv2.document.Table;
import com.amazonaws.services.dynamodbv2.model.AttributeDefinition;
import com.amazonaws.services.dynamodbv2.model.ConditionalCheckFailedException;
import com.amazonaws.services.dynamodbv2.model.KeySchemaElement;
import com.amazonaws.services.dynamodbv2.model.KeyType;
import com.amazonaws.services.dynamodbv2.model.ProvisionedThroughput;
import com.amazonaws.services.dynamodbv2.model.ResourceNotFoundException;
import com.amazonaws.services.dynamodbv2.model.ScalarAttributeType;
import com.github.dynamobee.changeset.ChangeEntry;
import com.github.dynamobee.exception.DynamobeeConfigurationException;
import com.github.dynamobee.exception.DynamobeeConnectionException;
import com.github.dynamobee.exception.DynamobeeLockException;


public class DynamobeeDao {
	private static final Logger logger = LoggerFactory.getLogger("Dynamobee dao");

	private static final String VALUE_LOCK = "LOCK";

	private DynamoDB dynamoDB;
	private String dynamobeeTableName;
	private Table dynamobeeTable;
	private boolean waitForLock;
	private long changeLogLockWaitTime;
	private long changeLogLockPollRate;
	private boolean throwExceptionIfCannotObtainLock;

	public DynamobeeDao(String dynamobeeTableName, boolean waitForLock, long changeLogLockWaitTime,
			long changeLogLockPollRate, boolean throwExceptionIfCannotObtainLock) {
		this.dynamobeeTableName = dynamobeeTableName;
		this.waitForLock = waitForLock;
		this.changeLogLockWaitTime = changeLogLockWaitTime;
		this.changeLogLockPollRate = changeLogLockPollRate;
		this.throwExceptionIfCannotObtainLock = throwExceptionIfCannotObtainLock;
	}

	public void connectDynamoDB(DynamoDB dynamoDB) throws DynamobeeConfigurationException {
		this.dynamoDB = dynamoDB;
		this.dynamobeeTable = findOrCreateDynamoBeeTable();
	}

	private Table findOrCreateDynamoBeeTable() {
		logger.info("Searching for an existing DynamoBee table; please wait...");
		try {
			Table table = dynamoDB.getTable(dynamobeeTableName);
			table.describe();
			logger.info("DynamoBee table found");
			return table;

		} catch (ResourceNotFoundException e) {
			logger.info("Attempting to create DynamoBee table; please wait...");
			Table table = dynamoDB.createTable(dynamobeeTableName,
					Arrays.asList(new KeySchemaElement(ChangeEntry.KEY_CHANGEID, KeyType.HASH)),
					Arrays.asList(new AttributeDefinition(ChangeEntry.KEY_CHANGEID, ScalarAttributeType.S)),
					new ProvisionedThroughput(1L, 1L));
			try {
				table.waitForActive();
			} catch (InterruptedException ex) {
				//ok
			}
			System.out.println("Success. DynamoBee Table status: " + table.getDescription().getTableStatus());
			return table;
		}
	}

	/**
	 * Try to acquire process lock
	 *
	 * @return true if successfully acquired, false otherwise
	 * @throws DynamobeeConnectionException exception
	 * @throws DynamobeeLockException exception
	 */
	public boolean acquireProcessLock() throws DynamobeeConnectionException, DynamobeeLockException {
		boolean acquired = this.acquireLock();

		if (!acquired && waitForLock) {
			long timeToGiveUp = new Date().getTime() + (changeLogLockWaitTime * 1000 * 60);
			while (!acquired && new Date().getTime() < timeToGiveUp) {
				acquired = this.acquireLock();
				if (!acquired) {
					logger.info("Waiting for changelog lock....");
					try {
						Thread.sleep(changeLogLockPollRate * 1000);
					} catch (InterruptedException e) {
						// nothing
					}
				}
			}
		}

		if (!acquired && throwExceptionIfCannotObtainLock) {
			logger.info("Dynamobee did not acquire process lock. Throwing exception.");
			throw new DynamobeeLockException("Could not acquire process lock");
		}

		return acquired;
	}

	public boolean acquireLock() {

		// acquire lock by attempting to insert the same value in the collection - if it already exists (i.e. lock held)
		// there will be an exception
		try {
			Item item = new Item()
					.withPrimaryKey(ChangeEntry.KEY_CHANGEID, VALUE_LOCK)
					.withLong(ChangeEntry.KEY_TIMESTAMP, new Date().getTime())
					.withString(ChangeEntry.KEY_AUTHOR, getHostName());

			this.dynamobeeTable.putItem(item, new Expected(ChangeEntry.KEY_CHANGEID).notExist());
		} catch (ConditionalCheckFailedException ex) {
			logger.warn("The lock has been already acquired.");
			return false;
		}
		return true;
	}

	private String getHostName() {
		try {
			return InetAddress.getLocalHost().getHostName();
		} catch (UnknownHostException e) {
			return "UnknownHost";
		}
	}

	public void releaseProcessLock() throws DynamobeeConnectionException {
		this.dynamobeeTable.deleteItem(ChangeEntry.KEY_CHANGEID, VALUE_LOCK);
	}

	public boolean isProccessLockHeld() throws DynamobeeConnectionException {
		return this.dynamobeeTable.getItem(ChangeEntry.KEY_CHANGEID, VALUE_LOCK) != null;
	}

	public boolean isNewChange(ChangeEntry changeEntry) throws DynamobeeConnectionException {
		return this.dynamobeeTable.getItem(ChangeEntry.KEY_CHANGEID, changeEntry.getChangeId()) == null;
	}

	public void save(ChangeEntry changeEntry) throws DynamobeeConnectionException {
		this.dynamobeeTable.putItem(changeEntry.buildFullDBObject());
	}

	public void setChangelogTableName(String changelogCollectionName) {
		this.dynamobeeTableName = changelogCollectionName;
	}

	public boolean isWaitForLock() {
		return waitForLock;
	}

	public void setWaitForLock(boolean waitForLock) {
		this.waitForLock = waitForLock;
	}

	public long getChangeLogLockWaitTime() {
		return changeLogLockWaitTime;
	}

	public void setChangeLogLockWaitTime(long changeLogLockWaitTime) {
		this.changeLogLockWaitTime = changeLogLockWaitTime;
	}

	public long getChangeLogLockPollRate() {
		return changeLogLockPollRate;
	}

	public void setChangeLogLockPollRate(long changeLogLockPollRate) {
		this.changeLogLockPollRate = changeLogLockPollRate;
	}

	public boolean isThrowExceptionIfCannotObtainLock() {
		return throwExceptionIfCannotObtainLock;
	}

	public void setThrowExceptionIfCannotObtainLock(boolean throwExceptionIfCannotObtainLock) {
		this.throwExceptionIfCannotObtainLock = throwExceptionIfCannotObtainLock;
	}

}
