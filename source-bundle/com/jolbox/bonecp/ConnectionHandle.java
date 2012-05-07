/**
 *  Copyright 2010 Wallace Wadge
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *        http://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jolbox.bonecp;

import java.lang.ref.Reference;
import java.lang.reflect.Proxy;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.SQLWarning;
import java.sql.Savepoint;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.collect.ImmutableSet;
import com.jolbox.bonecp.hooks.AcquireFailConfig;
import com.jolbox.bonecp.hooks.ConnectionHook;
import com.jolbox.bonecp.hooks.ConnectionState;
import com.jolbox.bonecp.proxy.TransactionRecoveryResult;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
// #ifdef JDK6
import java.sql.Array;
import java.sql.Blob;
import java.sql.Clob;
import java.sql.Struct;
import java.util.Properties;
import java.sql.NClob;
import java.sql.SQLClientInfoException;
import java.sql.SQLXML;
// #endif JDK6

/**
 * Connection handle wrapper around a JDBC connection.
 * 
 * @author wwadge
 * 
 */
public class ConnectionHandle implements Connection{
	/** Exception message. */
	private static final String STATEMENT_NOT_CLOSED = "Stack trace of location where statement was opened follows:\n%s";
	/** Exception message. */
	private static final String LOG_ERROR_MESSAGE = "Connection closed twice exception detected.\n%s\n%s\n";
	/** Exception message. */
	private static final String CLOSED_TWICE_EXCEPTION_MESSAGE = "Connection closed from thread [%s] was closed again.\nStack trace of location where connection was first closed follows:\n";
	/** Connection handle. */
	private Connection connection = null;
	/** Last time this connection was used by an application. */
	private long connectionLastUsedInMs = System.currentTimeMillis();
	/** Last time we sent a reset to this connection. */
	private long connectionLastResetInMs = System.currentTimeMillis();
	/** Time when this connection was created. */
	private long connectionCreationTimeInMs = System.currentTimeMillis();
	/** Pool handle. */
	private BoneCP pool;
	/**
	 * If true, this connection might have failed communicating with the
	 * database. We assume that exceptions should be rare here i.e. the normal
	 * case is assumed to succeed.
	 */
	protected boolean possiblyBroken;
	/** If true, we've called close() on this connection. */
	protected boolean logicallyClosed = false;
	/** Original partition. */
	private ConnectionPartition originatingPartition = null;
	/** Prepared Statement Cache. */
	private IStatementCache preparedStatementCache = null;
	/** Prepared Statement Cache. */
	private IStatementCache callableStatementCache = null;
	/** Logger handle. */
	private static Logger logger = LoggerFactory.getLogger(ConnectionHandle.class);
	/** An opaque handle for an application to use in any way it deems fit. */
	private Object debugHandle;
	/** Handle to the connection hook as defined in the config. */
	private ConnectionHook connectionHook;
	/** If true, give warnings if application tried to issue a close twice (for debugging only). */
	private boolean doubleCloseCheck;
	/** exception trace if doubleCloseCheck is enabled. */  
	private volatile String doubleCloseException = null;
	/** If true, log sql statements. */
	private boolean logStatementsEnabled;
	/** Set to true if we have statement caching enabled. */
	protected boolean statementCachingEnabled;
	/** The recorded actions list used to replay the transaction. */
	private List<ReplayLog> replayLog;
	/** If true, connection is currently playing back a saved transaction. */
	private boolean inReplayMode;
	/** Map of translations + result from last recovery. */
	protected TransactionRecoveryResult recoveryResult = new TransactionRecoveryResult();
	/** Connection url. */
	protected String url;	
	/** Keep track of the thread. */
	protected Thread threadUsingConnection;
	/** Configured max connection age. */
	private long maxConnectionAgeInMs;
	/** if true, we care about statistics. */
	private boolean statisticsEnabled;
	/** Statistics handle. */
	private Statistics statistics;
	/** Pointer to a thread that is monitoring this connection (for the case where closeConnectionWatch) is
	 * enabled.
	 */
	private volatile Thread threadWatch;
	/** Handle to pool.finalizationRefs. */
	private Map<Connection, Reference<ConnectionHandle>> finalizableRefs;
	/** If true, connection tracking is disabled in the config. */
	private boolean connectionTrackingDisabled;

	
	/*
	 * From: http://publib.boulder.ibm.com/infocenter/db2luw/v8/index.jsp?topic=/com.ibm.db2.udb.doc/core/r0sttmsg.htm
	 * Table 7. Class Code 08: Connection Exception
		SQLSTATE Value	  
		Value	Meaning
		08001	The application requester is unable to establish the connection.
		08002	The connection already exists.
		08003	The connection does not exist.
		08004	The application server rejected establishment of the connection.
		08007	Transaction resolution unknown.
		08502	The CONNECT statement issued by an application process running with a SYNCPOINT of TWOPHASE has failed, because no transaction manager is available.
		08504	An error was encountered while processing the specified path rename configuration file.
	 */
	/** SQL Failure codes indicating the database is broken/died (and thus kill off remaining connections). 
	  Anything else will be taken as the *connection* (not the db) being broken. Note: 08S01 is considered as connection failure in MySQL. 
          57P01 means that postgresql was restarted.
	 */
	private static final ImmutableSet<String> sqlStateDBFailureCodes = ImmutableSet.of("08001", "08007", "08S01", "57P01"); 

	/**
	 * Connection wrapper constructor
	 * 
	 * @param url
	 *            JDBC connection string
	 * @param username
	 *            user to use
	 * @param password
	 *            password for db
	 * @param pool
	 *            pool handle.
	 * @throws SQLException
	 *             on error
	 */
	public ConnectionHandle(String url, String username, String password,
			BoneCP pool) throws SQLException {


		this.pool = pool;
		this.url = url;
		this.connection = obtainInternalConnection();
		this.finalizableRefs = this.pool.getFinalizableRefs(); 
		this.connectionTrackingDisabled = pool.getConfig().isDisableConnectionTracking();
		this.statisticsEnabled = pool.getConfig().isStatisticsEnabled();
		this.statistics = pool.getStatistics();
		if (this.pool.getConfig().isTransactionRecoveryEnabled()){
			this.replayLog = new ArrayList<ReplayLog>(30);
			// this kick-starts recording everything
			this.connection = MemorizeTransactionProxy.memorize(this.connection, this);
		}

		this.maxConnectionAgeInMs = pool.getConfig().getMaxConnectionAge(TimeUnit.MILLISECONDS);
		this.doubleCloseCheck = pool.getConfig().isCloseConnectionWatch();
		this.logStatementsEnabled = pool.getConfig().isLogStatementsEnabled();
		int cacheSize = pool.getConfig().getStatementsCacheSize();
		if (cacheSize > 0) {
			this.preparedStatementCache = new StatementCache(cacheSize, pool.getConfig().isStatisticsEnabled(), pool.getStatistics());
			this.callableStatementCache = new StatementCache(cacheSize, pool.getConfig().isStatisticsEnabled(), pool.getStatistics());
			this.statementCachingEnabled = true;
		}
	}

	/** Obtains a database connection, retrying if necessary.
	 * @return A DB connection.
	 * @throws SQLException
	 */
	protected Connection obtainInternalConnection() throws SQLException {
		boolean tryAgain = false;
		Connection result = null;

		int acquireRetryAttempts = this.pool.getConfig().getAcquireRetryAttempts();
		long acquireRetryDelayInMs = this.pool.getConfig().getAcquireRetryDelayInMs();
		AcquireFailConfig acquireConfig = new AcquireFailConfig();
		acquireConfig.setAcquireRetryAttempts(new AtomicInteger(acquireRetryAttempts));
		acquireConfig.setAcquireRetryDelayInMs(acquireRetryDelayInMs);
		acquireConfig.setLogMessage("Failed to acquire connection");

		this.connectionHook = this.pool.getConfig().getConnectionHook();
		do{ 
			try { 
				// keep track of this hook.
				this.connection = this.pool.obtainRawInternalConnection();
				tryAgain = false;

				if (acquireRetryAttempts != this.pool.getConfig().getAcquireRetryAttempts()){
					logger.info("Successfully re-established connection to DB");
				}
				// call the hook, if available.
				if (this.connectionHook != null){
					this.connectionHook.onAcquire(this);
				}

				sendInitSQL();
				result = this.connection;
			} catch (SQLException e) {
				// call the hook, if available.
				if (this.connectionHook != null){
					tryAgain = this.connectionHook.onAcquireFail(e, acquireConfig);
				} else {
					logger.error("Failed to acquire connection. Sleeping for "+acquireRetryDelayInMs+"ms. Attempts left: "+acquireRetryAttempts, e);

					try {
						Thread.sleep(acquireRetryDelayInMs);
						if (acquireRetryAttempts > -1){
							tryAgain = (acquireRetryAttempts--) != 0;
						}
					} catch (InterruptedException e1) {
						tryAgain=false;
					}
				}
				if (!tryAgain){
					throw markPossiblyBroken(e);
				}
			}
		} while (tryAgain);

		return result;

	}

	/** Private constructor used solely for unit testing. 
	 * @param connection 
	 * @param preparedStatementCache 
	 * @param callableStatementCache 
	 * @param pool */
	public ConnectionHandle(Connection connection, IStatementCache preparedStatementCache, IStatementCache callableStatementCache, BoneCP pool){
		this.connection = connection;
		this.preparedStatementCache = preparedStatementCache;
		this.callableStatementCache = callableStatementCache;
		this.pool = pool;
		this.url=null;
		int cacheSize = pool.getConfig().getStatementsCacheSize();
		if (cacheSize > 0) {
			this.statementCachingEnabled = true;
		}
	}

	/** Sends any configured SQL init statement. 
	 * @throws SQLException on error
	 */
	public void sendInitSQL() throws SQLException {
		// fetch any configured setup sql.
		String initSQL = this.pool.getConfig().getInitSQL();
		if (initSQL != null){
			Statement stmt = this.connection.createStatement();
			stmt.execute(initSQL);
			stmt.close();
		}
	}



	/** 
	 * Given an exception, flag the connection (or database) as being potentially broken. If the exception is a data-specific exception,
	 * do nothing except throw it back to the application. 
	 * 
	 * @param e SQLException e
	 * @return SQLException for further processing
	 */
	protected SQLException markPossiblyBroken(SQLException e) {
		String state = e.getSQLState();
		ConnectionState connectionState = this.getConnectionHook() != null ? this.getConnectionHook().onMarkPossiblyBroken(this, state, e) : ConnectionState.NOP; 
		if (state == null){ // safety;
			state = "08999"; 
		}

		if ((sqlStateDBFailureCodes.contains(state) || connectionState.equals(ConnectionState.TERMINATE_ALL_CONNECTIONS)) && this.pool != null){
			logger.error("Database access problem. Killing off all remaining connections in the connection pool. SQL State = " + state);
			this.pool.terminateAllConnections();
		}

		// SQL-92 says:
		//		 Class values that begin with one of the <digit>s '5', '6', '7',
		//         '8', or '9' or one of the <simple Latin upper case letter>s 'I',
		//         'J', 'K', 'L', 'M', 'N', 'O', 'P', 'Q', 'R', 'S', 'T', 'U', 'V',
		//         'W', 'X', 'Y', or 'Z' are reserved for implementation-specified
		//         conditions.

		// FIXME: We should look into this.connection.getMetaData().getSQLStateType();
		// to determine if we have SQL:92 or X/OPEN sqlstatus codes.
		
		//		char firstChar = state.charAt(0);
		// if it's a communication exception, a mysql deadlock or an implementation-specific error code, flag this connection as being potentially broken.
		// state == 40001 is mysql specific triggered when a deadlock is detected
		// state == HY000 is firebird specific triggered when a connection is broken
		char firstChar = state.charAt(0);
		if (connectionState.equals(ConnectionState.CONNECTION_POSSIBLY_BROKEN) || state.equals("40001") || 
				state.equals("HY000") ||
				state.startsWith("08") ||  (firstChar >= '5' && firstChar <='9') /*|| (firstChar >='I' && firstChar <= 'Z')*/){
			this.possiblyBroken = true;
		}

		// Notify anyone who's interested
		if (this.possiblyBroken  && (this.getConnectionHook() != null)){
			this.possiblyBroken = this.getConnectionHook().onConnectionException(this, state, e);
		}

		return e;
	}


	public void clearWarnings() throws SQLException {
		checkClosed();
		try {
			this.connection.clearWarnings();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	/**
	 * Checks if the connection is (logically) closed and throws an exception if it is.
	 * 
	 * @throws SQLException
	 *             on error
	 * 
	 * 
	 */
	private void checkClosed() throws SQLException {
		if (this.logicallyClosed) {
			throw new SQLException("Connection is closed!");
		}
	}

	/**
	 * Release the connection if called. 
	 * 
	 * @throws SQLException Never really thrown
	 */
	public void close() throws SQLException {
		try {
			if (!this.logicallyClosed) {
				this.logicallyClosed = true;
				this.threadUsingConnection = null;
				if (this.threadWatch != null){
					this.threadWatch.interrupt(); // if we returned the connection to the pool, terminate thread watch thread if it's
												  // running even if thread is still alive (eg thread has been recycled for use in some
												  // container).
					this.threadWatch = null;
				}
				this.pool.releaseConnection(this);

				if (this.doubleCloseCheck){
					this.doubleCloseException = this.pool.captureStackTrace(CLOSED_TWICE_EXCEPTION_MESSAGE);
				}
			} else {
				if (this.doubleCloseCheck && this.doubleCloseException != null){
					String currentLocation = this.pool.captureStackTrace("Last closed trace from thread ["+Thread.currentThread().getName()+"]:\n");
					logger.error(String.format(LOG_ERROR_MESSAGE, this.doubleCloseException, currentLocation));
				}
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}


	/**
	 * Close off the connection.
	 * 
	 * @throws SQLException
	 */
	protected void internalClose() throws SQLException {
		try {
			clearStatementCaches(true);
			if (this.connection != null){ // safety!
				this.connection.close();
				
				if (!this.connectionTrackingDisabled && this.finalizableRefs != null){
					this.finalizableRefs.remove(this.connection);
				}
			}
			this.logicallyClosed = true;
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void commit() throws SQLException {
		checkClosed();
		try {
			this.connection.commit();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}
	// #ifdef JDK6
	public Properties getClientInfo() throws SQLException {
		Properties result = null;
		checkClosed();
		try {
			result = this.connection.getClientInfo();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public String getClientInfo(String name) throws SQLException {
		String result = null;
		checkClosed();
		try {
			result = this.connection.getClientInfo(name);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public boolean isValid(int timeout) throws SQLException {
		boolean result = false;
		checkClosed();
		try {
			result = this.connection.isValid(timeout);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	@Override
	public boolean isWrapperFor(Class<?> iface) throws SQLException {
		return this.connection.isWrapperFor(iface);
	}

	@Override
	public <T> T unwrap(Class<T> iface) throws SQLException {
		return this.connection.unwrap(iface);
	}

	@Override
	public void setClientInfo(Properties properties) throws SQLClientInfoException {
		this.connection.setClientInfo(properties);
	}

	@Override
	public void setClientInfo(String name, String value) throws SQLClientInfoException {
		this.connection.setClientInfo(name, value);
	}

	@Override
	public Struct createStruct(String typeName, Object[] attributes)
	throws SQLException {
		Struct result = null;
		checkClosed();
		try {
			result = this.connection.createStruct(typeName, attributes);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	@Override	
	public Array createArrayOf(String typeName, Object[] elements)
	throws SQLException {
		Array result = null;
		checkClosed();
		try {
			result = this.connection.createArrayOf(typeName, elements);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return result;
	}
	@Override
	public Blob createBlob() throws SQLException {
		Blob result = null;
		checkClosed();
		try {
			result = this.connection.createBlob();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	@Override
	public Clob createClob() throws SQLException {
		Clob result = null;
		checkClosed();
		try {
			result = this.connection.createClob();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return result;

	}

	public NClob createNClob() throws SQLException {
		NClob result = null;
		checkClosed();
		try {
			result = this.connection.createNClob();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public SQLXML createSQLXML() throws SQLException {
		SQLXML result = null;
		checkClosed();
		try {
			result = this.connection.createSQLXML();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}
	// #endif JDK6

	public Statement createStatement() throws SQLException {
		Statement result = null;
		checkClosed();
		try {
			result =new StatementHandle(this.connection.createStatement(), this, this.logStatementsEnabled);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public Statement createStatement(int resultSetType, int resultSetConcurrency)
	throws SQLException {
		Statement result = null;
		checkClosed();
		try {
			result = new StatementHandle(this.connection.createStatement(resultSetType, resultSetConcurrency), this, this.logStatementsEnabled);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public Statement createStatement(int resultSetType,
			int resultSetConcurrency, int resultSetHoldability)
	throws SQLException {
		Statement result = null;
		checkClosed();
		try {
			result = new StatementHandle(this.connection.createStatement(resultSetType, resultSetConcurrency, resultSetHoldability), this, this.logStatementsEnabled);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return result;
	}


	public boolean getAutoCommit() throws SQLException {
		boolean result = false;
		checkClosed();
		try {
			result = this.connection.getAutoCommit();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public String getCatalog() throws SQLException {
		String result = null;
		checkClosed();
		try {
			result = this.connection.getCatalog();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}


	public int getHoldability() throws SQLException {
		int result = 0;
		checkClosed();
		try {
			result = this.connection.getHoldability();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return result;
	}

	public DatabaseMetaData getMetaData() throws SQLException {
		DatabaseMetaData result = null;
		checkClosed();
		try {
			result = this.connection.getMetaData();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public int getTransactionIsolation() throws SQLException {
		int result = 0;
		checkClosed();
		try {
			result = this.connection.getTransactionIsolation();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public Map<String, Class<?>> getTypeMap() throws SQLException {
		Map<String, Class<?>> result = null;
		checkClosed();
		try {
			result = this.connection.getTypeMap();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public SQLWarning getWarnings() throws SQLException {
		SQLWarning result = null;
		checkClosed();
		try {
			result = this.connection.getWarnings();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}


	/** Returns true if this connection has been (logically) closed.
	 * @return the logicallyClosed setting.
	 */
	//	@Override
	public boolean isClosed() {
		return this.logicallyClosed;
	}

	public boolean isReadOnly() throws SQLException {
		boolean result = false;
		checkClosed();
		try {
			result = this.connection.isReadOnly();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public String nativeSQL(String sql) throws SQLException {
		String result = null;
		checkClosed();
		try {
			result = this.connection.nativeSQL(sql);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public CallableStatement prepareCall(String sql) throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = sql;
				result = this.callableStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new CallableStatementHandle(this.connection.prepareCall(sql), 
						sql, this, cacheKey, this.callableStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (CallableStatement) result;	
	}

	public CallableStatement prepareCall(String sql, int resultSetType,	int resultSetConcurrency) throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = this.callableStatementCache.calculateCacheKey(sql, resultSetType, resultSetConcurrency);
				result = this.callableStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new CallableStatementHandle(this.connection.prepareCall(sql, resultSetType, resultSetConcurrency), 
						sql, this, cacheKey, this.callableStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (CallableStatement) result;	
	}

	public CallableStatement prepareCall(String sql, int resultSetType,
			int resultSetConcurrency, int resultSetHoldability) throws SQLException {

		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = this.callableStatementCache.calculateCacheKey(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
				result = this.callableStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new CallableStatementHandle(this.connection.prepareCall(sql, resultSetType, resultSetConcurrency, resultSetHoldability), 
						sql, this, cacheKey, this.callableStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (CallableStatement) result;	
	}

	public PreparedStatement prepareStatement(String sql) throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;
		
		checkClosed(); 

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = sql;
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result =  new PreparedStatementHandle(this.connection.prepareStatement(sql), sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}


			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}

			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
			
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return (PreparedStatement) result;
	}


	public PreparedStatement prepareStatement(String sql, int autoGeneratedKeys) throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart = 0;
			if (this.statisticsEnabled){
				statStart  = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = this.preparedStatementCache.calculateCacheKey(sql, autoGeneratedKeys);
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new PreparedStatementHandle(this.connection.prepareStatement(sql, autoGeneratedKeys), sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}
			
			if (this.pool.closeConnectionWatch  && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}

			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}

		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return (PreparedStatement) result;

	}

	public PreparedStatement prepareStatement(String sql, int[] columnIndexes)
	throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}

			if (this.statementCachingEnabled) {
				cacheKey = this.preparedStatementCache.calculateCacheKey(sql, columnIndexes);
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new PreparedStatementHandle(this.connection.prepareStatement(sql, columnIndexes), 
						sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch  && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}

		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (PreparedStatement) result;
	}

	public PreparedStatement prepareStatement(String sql, String[] columnNames)
	throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = this.preparedStatementCache.calculateCacheKey(sql, columnNames);
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new PreparedStatementHandle(this.connection.prepareStatement(sql, columnNames), 
						sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}

			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (PreparedStatement) result;

	}

	public PreparedStatement prepareStatement(String sql, int resultSetType,  int resultSetConcurrency) throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			if (this.statementCachingEnabled) {
				cacheKey = this.preparedStatementCache.calculateCacheKey(sql, resultSetType, resultSetConcurrency);
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new PreparedStatementHandle(this.connection.prepareStatement(sql, resultSetType, resultSetConcurrency), 
						sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (PreparedStatement) result;

	}

	public PreparedStatement prepareStatement(String sql, int resultSetType, int resultSetConcurrency, int resultSetHoldability)
	throws SQLException {
		StatementHandle result = null;
		String cacheKey = null;

		checkClosed();

		try {
			long statStart=0;
			if (this.statisticsEnabled){
				statStart = System.nanoTime();
			}
			
			if (this.statementCachingEnabled) {
				cacheKey = this.preparedStatementCache.calculateCacheKey(sql, resultSetType, resultSetConcurrency, resultSetHoldability);
				result = this.preparedStatementCache.get(cacheKey);
			}

			if (result == null){
				result = new PreparedStatementHandle(this.connection.prepareStatement(sql, resultSetType, resultSetConcurrency, resultSetHoldability), 
						sql, this, cacheKey, this.preparedStatementCache);
				result.setLogicallyOpen();
			}

			if (this.pool.closeConnectionWatch && this.statementCachingEnabled){ // debugging mode enabled?
				result.setOpenStackTrace(this.pool.captureStackTrace(STATEMENT_NOT_CLOSED));
			}
			if (this.statisticsEnabled){
				this.statistics.addStatementPrepareTime(System.nanoTime()-statStart);
				this.statistics.incrementStatementsPrepared();
			}
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}

		return (PreparedStatement) result;
	}

	public void releaseSavepoint(Savepoint savepoint) throws SQLException {
		checkClosed();
		try {
			this.connection.releaseSavepoint(savepoint);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);

		}
	}

	public void rollback() throws SQLException {
		checkClosed();
		try {
			this.connection.rollback();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void rollback(Savepoint savepoint) throws SQLException {
		checkClosed();
		try {
			this.connection.rollback(savepoint);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void setAutoCommit(boolean autoCommit) throws SQLException {
		checkClosed();
		try {
			this.connection.setAutoCommit(autoCommit);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void setCatalog(String catalog) throws SQLException {
		checkClosed();
		try {
			this.connection.setCatalog(catalog);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}


	public void setHoldability(int holdability) throws SQLException {
		checkClosed();
		try {
			this.connection.setHoldability(holdability);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void setReadOnly(boolean readOnly) throws SQLException {
		checkClosed();
		try {
			this.connection.setReadOnly(readOnly);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public Savepoint setSavepoint() throws SQLException {
		checkClosed();
		Savepoint result = null;
		try {
			result = this.connection.setSavepoint();
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public Savepoint setSavepoint(String name) throws SQLException {
		checkClosed();
		Savepoint result = null;
		try {
			result = this.connection.setSavepoint(name);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
		return result;
	}

	public void setTransactionIsolation(int level) throws SQLException {
		checkClosed();
		try {
			this.connection.setTransactionIsolation(level);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	public void setTypeMap(Map<String, Class<?>> map) throws SQLException {
		checkClosed();
		try {
			this.connection.setTypeMap(map);
		} catch (SQLException e) {
			throw markPossiblyBroken(e);
		}
	}

	/**
	 * @return the connectionLastUsed
	 */
	public long getConnectionLastUsedInMs() {
		return this.connectionLastUsedInMs;
	}

	/**
	 * Deprecated. Use {@link #getConnectionLastUsedInMs()} instead.
	 * @return the connectionLastUsed
	 * @deprecated Use {@link #getConnectionLastUsedInMs()} instead.
	 */
	@Deprecated
	public long getConnectionLastUsed() {
		return getConnectionLastUsedInMs();
	}

	/**
	 * @param connectionLastUsed
	 *            the connectionLastUsed to set
	 */
	protected void setConnectionLastUsedInMs(long connectionLastUsed) {
		this.connectionLastUsedInMs = connectionLastUsed;
	}

	/**
	 * @return the connectionLastReset
	 */
	public long getConnectionLastResetInMs() {
		return this.connectionLastResetInMs;
	}

	/** Deprecated. Use {@link #getConnectionLastResetInMs()} instead.
	 * @return the connectionLastReset
	 * @deprecated Please use {@link #getConnectionLastResetInMs()} instead
	 */
	@Deprecated
	public long getConnectionLastReset() {
		return getConnectionLastResetInMs();
	}

	
	/**
	 * @param connectionLastReset
	 *            the connectionLastReset to set
	 */
	protected void setConnectionLastResetInMs(long connectionLastReset) {
		this.connectionLastResetInMs = connectionLastReset;
	}

	/**
	 * Gets true if connection has triggered an exception at some point.
	 * 
	 * @return true if the connection has triggered an error
	 */
	public boolean isPossiblyBroken() {
		return this.possiblyBroken;
	}


	/**
	 * Gets the partition this came from.
	 * 
	 * @return the partition this came from
	 */
	public ConnectionPartition getOriginatingPartition() {
		return this.originatingPartition;
	}

	/**
	 * Sets Originating partition
	 * 
	 * @param originatingPartition
	 *            to set
	 */
	protected void setOriginatingPartition(ConnectionPartition originatingPartition) {
		this.originatingPartition = originatingPartition;
	}

	/**
	 * Renews this connection, i.e. Sets this connection to be logically open
	 * (although it was never really physically closed)
	 */
	protected void renewConnection() {
		this.logicallyClosed = false;
		this.threadUsingConnection = Thread.currentThread();
		if (this.doubleCloseCheck){
			this.doubleCloseException = null;
		}
	}


	/** Clears out the statement handles.
	 * @param internalClose if true, close the inner statement handle too. 
	 */
	protected void clearStatementCaches(boolean internalClose) {

		if (this.statementCachingEnabled){ // safety

			if (internalClose){
				this.callableStatementCache.clear();
				this.preparedStatementCache.clear();
			} else {
				if (this.pool.closeConnectionWatch){ // debugging enabled?
					this.callableStatementCache.checkForProperClosure();
					this.preparedStatementCache.checkForProperClosure();
				}
			}
		}
	}

	/** Returns a debug handle as previously set by an application
	 * @return DebugHandle
	 */
	public Object getDebugHandle() {
		return this.debugHandle;
	}

	/** Sets a debugHandle, an object that is not used by the connection pool at all but may be set by an application to track
	 * this particular connection handle for any purpose it deems fit.
	 * @param debugHandle any object.
	 */
	public void setDebugHandle(Object debugHandle) {
		this.debugHandle = debugHandle;
	}

	/** Deprecated. Please use getInternalConnection() instead. 
	 *  
	 * @return the raw connection
	 */
	@Deprecated
	public Connection getRawConnection() {
		return getInternalConnection();
	}

	/** Returns the internal connection as obtained via the JDBC driver.
	 * @return the raw connection
	 */
	public Connection getInternalConnection() {
		return this.connection;
	}

	/** Returns the configured connection hook object.
	 * @return the connectionHook that was set in the config
	 */
	public ConnectionHook getConnectionHook() {
		return this.connectionHook;
	}

	/** Returns true if logging of statements is enabled
	 * @return logStatementsEnabled status
	 */
	public boolean isLogStatementsEnabled() {
		return this.logStatementsEnabled;
	}

	/** Enable or disable logging of this connection.
	 * @param logStatementsEnabled true to enable logging, false to disable.
	 */
	public void setLogStatementsEnabled(boolean logStatementsEnabled) {
		this.logStatementsEnabled = logStatementsEnabled;
	}

	/**
	 * @return the inReplayMode
	 */
	protected boolean isInReplayMode() {
		return this.inReplayMode;
	}

	/**
	 * @param inReplayMode the inReplayMode to set
	 */
	protected void setInReplayMode(boolean inReplayMode) {
		this.inReplayMode = inReplayMode;
	}

	/** Sends a test query to the underlying connection and return true if connection is alive.
	 * @return True if connection is valid, false otherwise.
	 */
	public boolean isConnectionAlive(){
		return this.pool.isConnectionHandleAlive(this);
	}

	/** Sets the internal connection to use. Be careful how to use this method, normally you should never need it! This is here
	 * for odd use cases only!
	 * @param rawConnection to set
	 */
	public void setInternalConnection(Connection rawConnection) {
		this.connection = rawConnection;
	}

	/** Returns a handle to the global pool from where this connection was obtained.
	 * @return BoneCP handle
	 */
	public BoneCP getPool() {
		return this.pool;
	}

	/** Returns transaction history log
	 * @return replay list
	 */
	public List<ReplayLog> getReplayLog() {
		return this.replayLog;
	}

	/** Sets the transaction history log
	 * @param replayLog to set.
	 */
	protected void setReplayLog(List<ReplayLog> replayLog) {
		this.replayLog = replayLog;
	}

	/** This method will be intercepted by the proxy if it is enabled to return the internal target.
	 * @return the target.
	 */
	public Object getProxyTarget(){
		try {
			return Proxy.getInvocationHandler(this.connection).invoke(null, this.getClass().getMethod("getProxyTarget"), null);
		} catch (Throwable t) {
			throw new RuntimeException("BoneCP: Internal error - transaction replay log is not turned on?", t);
		}
	}

	/** Returns the thread that is currently utilizing this connection.
	 * @return the threadUsingConnection
	 */
	public Thread getThreadUsingConnection() {
		return this.threadUsingConnection;
	}

	/**
	 * Deprecated. Use {@link #getConnectionCreationTimeInMs()} instead.
	 * @return connectionCreationTime
	 * @deprecated please use {@link #getConnectionCreationTimeInMs()} instead.
	 */
	@Deprecated
	public long getConnectionCreationTime() {
		return getConnectionCreationTimeInMs();
	}

	/**
	 * Returns the connectionCreationTime field.
	 * @return connectionCreationTime
	 */
	public long getConnectionCreationTimeInMs() {
		return this.connectionCreationTimeInMs;
	}

	/** Returns true if the given connection has exceeded the maxConnectionAge.
	 * @return true if the connection has expired.
	 */
	public boolean isExpired() {
		return isExpired(System.currentTimeMillis());
	}

	/** Returns true if the given connection has exceeded the maxConnectionAge.
	 * @param currentTime current time to use.
	 * @return true if the connection has expired.
	 */
	protected boolean isExpired(long currentTime) {
		return this.maxConnectionAgeInMs > 0 && (currentTime - this.connectionCreationTimeInMs) > this.maxConnectionAgeInMs;
	}

	/**
	 * Sets the thread watching over this connection.
	 * @param threadWatch the threadWatch to set
	 */
	protected void setThreadWatch(Thread threadWatch) {
		this.threadWatch = threadWatch;
	}

	/**
	 * Returns the thread watching over this connection.
	 * @return threadWatch
	 */
	public Thread getThreadWatch() {
		return this.threadWatch;
	}
	

}