/**********************************************************************
Copyright (c) 2009 Andy Jefferson and others. All rights reserved.
Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.

Contributors:
    ...
**********************************************************************/
package org.datanucleus.store.rdbms.query;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;

import org.datanucleus.ExecutionContext;
import org.datanucleus.FetchPlanForClass;
import org.datanucleus.exceptions.NucleusDataStoreException;
import org.datanucleus.exceptions.NucleusException;
import org.datanucleus.exceptions.NucleusUserException;
import org.datanucleus.metadata.AbstractClassMetaData;
import org.datanucleus.metadata.AbstractMemberMetaData;
import org.datanucleus.metadata.InheritanceStrategy;
import org.datanucleus.metadata.RelationType;
import org.datanucleus.store.StoreManager;
import org.datanucleus.store.connection.ManagedConnection;
import org.datanucleus.store.connection.ManagedConnectionResourceListener;
import org.datanucleus.store.rdbms.mapping.java.AbstractContainerMapping;
import org.datanucleus.store.rdbms.mapping.java.JavaTypeMapping;
import org.datanucleus.store.query.AbstractJPQLQuery;
import org.datanucleus.store.query.CandidateIdsQueryResult;
import org.datanucleus.store.query.Query;
import org.datanucleus.store.query.QueryInterruptedException;
import org.datanucleus.store.query.QueryManager;
import org.datanucleus.store.query.QueryResult;
import org.datanucleus.store.query.QueryTimeoutException;
import org.datanucleus.store.query.QueryUtils;
import org.datanucleus.store.query.expression.Expression;
import org.datanucleus.store.query.inmemory.JPQLInMemoryEvaluator;
import org.datanucleus.store.rdbms.RDBMSPropertyNames;
import org.datanucleus.store.rdbms.RDBMSStoreManager;
import org.datanucleus.store.rdbms.SQLController;
import org.datanucleus.store.rdbms.adapter.DatastoreAdapter;
import org.datanucleus.store.rdbms.query.RDBMSQueryCompilation.StatementCompilation;
import org.datanucleus.store.rdbms.scostore.IteratorStatement;
import org.datanucleus.store.rdbms.sql.DeleteStatement;
import org.datanucleus.store.rdbms.sql.InsertStatement;
import org.datanucleus.store.rdbms.sql.SQLStatement;
import org.datanucleus.store.rdbms.sql.SQLStatementHelper;
import org.datanucleus.store.rdbms.sql.SQLTable;
import org.datanucleus.store.rdbms.sql.SQLJoin.JoinType;
import org.datanucleus.store.rdbms.sql.SelectStatement;
import org.datanucleus.store.rdbms.sql.SelectStatementGenerator;
import org.datanucleus.store.rdbms.sql.UpdateStatement;
import org.datanucleus.store.rdbms.sql.expression.ColumnExpression;
import org.datanucleus.store.rdbms.sql.expression.SQLExpression;
import org.datanucleus.store.rdbms.table.DatastoreClass;
import org.datanucleus.store.schema.table.SurrogateColumnType;
import org.datanucleus.store.types.SCOUtils;
import org.datanucleus.util.ClassUtils;
import org.datanucleus.util.Localiser;
import org.datanucleus.util.NucleusLogger;
import org.datanucleus.util.StringUtils;

/**
 * RDBMS representation of a JPQL query for use by DataNucleus.
 * The query can be specified via method calls, or via a single-string form.
 * This implementation uses the generic query compilation in "org.datanucleus.query".
 * There are the following main ways of running a query here
 * <ul>
 * <li>Totally in the datastore (no candidate collection specified, and no in-memory eval).</li>
 * <li>Totally in-memory (candidate collection specified, and in-memory eval)</li>
 * <li>Retrieve candidates from datastore (no candidate collection), and evaluate in-memory</li>
 * </ul>
 */
public class JPQLQuery extends AbstractJPQLQuery
{
    private static final long serialVersionUID = -3735379324740714088L;

    /** Extension for whether to convert "== ?" with null parameter to "IS NULL". Defaults to false to comply with JPA spec 4.11. */
    public static final String EXTENSION_USE_IS_NULL_WHEN_EQUALS_NULL_PARAM = "datanucleus.useIsNullWhenEqualsNullParameter";

    /** Extension to add NOWAIT when using FOR UPDATE (when supported). */
    public static final String EXTENSION_FOR_UPDATE_NOWAIT = "datanucleus.forUpdateNowait";

    /** Extension to define the JOIN TYPE to use when navigating single-valued relations, when part of the filter. */
    public static final String EXTENSION_NAVIGATION_JOIN_TYPE_FILTER = "datanucleus.query.jpql.navigationJoinTypeForFilter";

    /** Extension to define the JOIN TYPE to use when navigating single-valued relations. */
    public static final String EXTENSION_NAVIGATION_JOIN_TYPE = "datanucleus.query.jpql.navigationJoinType";

    /** Extension to not apply a discriminator restriction on the candidate of the query. */
    public static final String EXTENSION_CANDIDATE_DONT_RESTRICT_DISCRIMINATOR = "datanucleus.query.dontRestrictDiscriminator";

    /** Extension to include soft-deleted objects in any results. */
    public static final String EXTENSION_INCLUDE_SOFT_DELETES = "datanucleus.query.includeSoftDeletes";

    /** The compilation of the query for this datastore. Not applicable if totally in-memory. */
    protected transient RDBMSQueryCompilation datastoreCompilation;

    boolean statementReturnsEmpty = false;

    /**
     * Constructs a new query instance that uses the given object manager.
     * @param storeMgr StoreManager for this query
     * @param ec execution context
     */
    public JPQLQuery(StoreManager storeMgr, ExecutionContext ec)
    {
        this(storeMgr, ec, (JPQLQuery) null);
    }

    /**
     * Constructs a new query instance having the same criteria as the given query.
     * @param storeMgr StoreManager for this query
     * @param ec execution context
     * @param q The query from which to copy criteria.
     */
    public JPQLQuery(StoreManager storeMgr, ExecutionContext ec, JPQLQuery q)
    {
        super(storeMgr, ec, q);
    }

    /**
     * Constructor for a JPQL query where the query is specified using the "Single-String" format.
     * @param storeMgr StoreManager for this query
     * @param ec The ExecutionContext
     * @param query The single-string query form
     */
    public JPQLQuery(StoreManager storeMgr, ExecutionContext ec, String query)
    {
        super(storeMgr, ec, query);
    }

    @Override
    public void setImplicitParameter(int position, Object value)
    {
        if (datastoreCompilation != null && !datastoreCompilation.isPrecompilable())
        {
            // Force recompile since parameter value set and not compilable without parameter values
            datastoreCompilation = null;
        }
        super.setImplicitParameter(position, value);
    }

    @Override
    public void setImplicitParameter(String name, Object value)
    {
        if (datastoreCompilation != null && !datastoreCompilation.isPrecompilable())
        {
            // Force recompile since parameter value set and not compilable without parameter values
            datastoreCompilation = null;
        }
        super.setImplicitParameter(name, value);
    }

    /**
     * Utility to remove any previous compilation of this Query.
     */
    protected void discardCompiled()
    {
        super.discardCompiled();

        datastoreCompilation = null;
    }

    /**
     * Method to return if the query is compiled.
     * @return Whether it is compiled
     */
    protected boolean isCompiled()
    {
        if (candidateCollection != null)
        {
            // Don't need datastore compilation here since evaluating in-memory
            return compilation != null;
        }

        // Need both to be present to say "compiled"
        if (compilation == null || datastoreCompilation == null)
        {
            return false;
        }
        if (!datastoreCompilation.isPrecompilable())
        {
            NucleusLogger.GENERAL.info("Query compiled but not precompilable so ditching datastore compilation");
            datastoreCompilation = null;
            return false;
        }
        return true;
    }

    /**
     * Method to get key for query cache
     * @return The cache key
     */
    protected String getQueryCacheKey()
    {
        if (getSerializeRead() != null && getSerializeRead())
        {
            return super.getQueryCacheKey() + " FOR UPDATE";
        }
        return super.getQueryCacheKey();
    }

    /**
     * Method to compile the JPQL query.
     * Uses the superclass to compile the generic query populating the "compilation", and then generates
     * the datastore-specific "datastoreCompilation".
     * @param parameterValues Map of param values keyed by param name (if available at compile time)
     */
    protected synchronized void compileInternal(Map parameterValues)
    {
        if (isCompiled())
        {
            return;
        }

        if (getExtension(EXTENSION_INCLUDE_SOFT_DELETES) != null)
        {
            // If using an extension that can change the datastore query then evict any existing compilation
            QueryManager qm = getQueryManager();
            qm.removeQueryCompilation(Query.LANGUAGE_JPQL, getQueryCacheKey());
        }

        // Compile the generic query expressions
        super.compileInternal(parameterValues);

        boolean inMemory = evaluateInMemory();
        if (candidateCollection != null)
        {
            // Everything done in-memory so just return now (don't need datastore compilation)
            // TODO Maybe apply the result class checks ?
            return;
        }

        if (candidateClass == null || candidateClassName == null)
        {
            candidateClass = compilation.getCandidateClass();
            candidateClassName = candidateClass.getName();
        }

        // Create the SQL statement, and its result/parameter definitions
        RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
        QueryManager qm = getQueryManager();
        String datastoreKey = storeMgr.getQueryCacheKey();
        String queryCacheKey = getQueryCacheKey();

        if (useCaching() && queryCacheKey != null)
        {
            // Check if we have any parameters set to null, since this can invalidate a datastore compilation
            // e.g " field == :val" can be "COL IS NULL" or "COL = <val>"
            boolean nullParameter = false;
            if (parameterValues != null)
            {
                Iterator iter = parameterValues.values().iterator();
                while (iter.hasNext())
                {
                    Object val = iter.next();
                    if (val == null)
                    {
                        nullParameter = true;
                        break;
                    }
                }
            }

            if (!nullParameter)
            {
                // Allowing caching so try to find compiled (datastore) query
                datastoreCompilation = (RDBMSQueryCompilation)qm.getDatastoreQueryCompilation(datastoreKey, getLanguage(), queryCacheKey);
                if (datastoreCompilation != null)
                {
                    // Cached compilation exists for this datastore so reuse it
                    return;
                }
            }
        }

        // No cached compilation for this query in this datastore so compile it
        AbstractClassMetaData acmd = getCandidateClassMetaData();
        if (type == QueryType.BULK_INSERT)
        {
            datastoreCompilation = new RDBMSQueryCompilation();
            compileQueryInsert(parameterValues, acmd);
        }
        else if (type == QueryType.BULK_UPDATE)
        {
            datastoreCompilation = new RDBMSQueryCompilation();
            compileQueryUpdate(parameterValues, acmd);
        }
        else if (type == QueryType.BULK_DELETE)
        {
            datastoreCompilation = new RDBMSQueryCompilation();
            compileQueryDelete(parameterValues, acmd);
        }
        else
        {
            datastoreCompilation = new RDBMSQueryCompilation();
            if (inMemory)
            {
                // Generate statement to just retrieve all candidate objects for later processing
                compileQueryToRetrieveCandidates(parameterValues, acmd);
            }
            else
            {
                // Generate statement to perform the full query in the datastore
                compileQueryFull(parameterValues, acmd);

                if (result != null)
                {
                    StatementResultMapping resultMapping = datastoreCompilation.getResultDefinition();
                    for (int i=0;i<resultMapping.getNumberOfResultExpressions();i++)
                    {
                        Object stmtMap = resultMapping.getMappingForResultExpression(i);
                        if (stmtMap instanceof StatementMappingIndex)
                        {
                            StatementMappingIndex idx = (StatementMappingIndex)stmtMap;
                            AbstractMemberMetaData mmd = idx.getMapping().getMemberMetaData();
                            if (mmd != null)
                            {
                                if (idx.getMapping() instanceof AbstractContainerMapping && idx.getMapping().getNumberOfColumnMappings() != 1)
                                {
                                    throw new NucleusUserException(Localiser.msg("021213"));
                                }
                            }
                        }
                    }
                }
            }

            if (resultClass != null && result != null)
            {
                // Do as PrivilegedAction since uses reflection
                AccessController.doPrivileged(new PrivilegedAction()
                {
                    public Object run()
                    {
                        // Check that this class has the necessary constructor/setters/fields to be used
                        StatementResultMapping resultMapping = datastoreCompilation.getResultDefinition();
                        if (QueryUtils.resultClassIsSimple(resultClass.getName()))
                        {
                            if (resultMapping.getNumberOfResultExpressions() > 1)
                            {
                                // Invalid number of result expressions
                                throw new NucleusUserException(Localiser.msg("021201", resultClass.getName()));
                            }

                            Object stmtMap = resultMapping.getMappingForResultExpression(0);
                            // TODO Handle StatementNewObjectMapping
                            StatementMappingIndex idx = (StatementMappingIndex)stmtMap;
                            Class exprType = idx.getMapping().getJavaType();
                            boolean typeConsistent = false;
                            if (exprType == resultClass)
                            {
                                typeConsistent = true;
                            }
                            else if (exprType.isPrimitive())
                            {
                                Class resultClassPrimitive = ClassUtils.getPrimitiveTypeForType(resultClass);
                                if (resultClassPrimitive == exprType)
                                {
                                    typeConsistent = true;
                                }
                            }
                            if (!typeConsistent)
                            {
                                // Inconsistent expression type not matching the result class type
                                throw new NucleusUserException(Localiser.msg("021202", resultClass.getName(), exprType));
                            }
                        }
                        else if (QueryUtils.resultClassIsUserType(resultClass.getName()))
                        {
                            // Check for valid constructor (either using param types, or using default ctr)
                            Class[] ctrTypes = new Class[resultMapping.getNumberOfResultExpressions()];
                            for (int i=0;i<ctrTypes.length;i++)
                            {
                                Object stmtMap = resultMapping.getMappingForResultExpression(i);
                                if (stmtMap instanceof StatementMappingIndex)
                                {
                                    ctrTypes[i] = ((StatementMappingIndex)stmtMap).getMapping().getJavaType();
                                }
                                else if (stmtMap instanceof StatementNewObjectMapping)
                                {
                                    // TODO Handle this
                                }
                            }
                            Constructor ctr = ClassUtils.getConstructorWithArguments(resultClass, ctrTypes);
                            if (ctr == null && !ClassUtils.hasDefaultConstructor(resultClass))
                            {
                                // No valid constructor found!
                                throw new NucleusUserException(Localiser.msg("021205", resultClass.getName()));
                            }
                            else if (ctr == null)
                            {
                                // We are using default constructor, so check the types of the result expressions for means of input
                                for (int i=0;i<resultMapping.getNumberOfResultExpressions();i++)
                                {
                                    Object stmtMap = resultMapping.getMappingForResultExpression(i);
                                    if (stmtMap instanceof StatementMappingIndex)
                                    {
                                        StatementMappingIndex mapIdx = (StatementMappingIndex)stmtMap;
                                        AbstractMemberMetaData mmd = mapIdx.getMapping().getMemberMetaData();
                                        String fieldName = mapIdx.getColumnAlias();
                                        Class fieldType = mapIdx.getMapping().getJavaType();
                                        if (fieldName == null && mmd != null)
                                        {
                                            fieldName = mmd.getName();
                                        }

                                        if (fieldName != null)
                                        {
                                            // Check for the field of that name in the result class
                                            Class resultFieldType = null;
                                            boolean publicField = true;
                                            try
                                            {
                                                Field fld = resultClass.getDeclaredField(fieldName);
                                                resultFieldType = fld.getType();

                                                // Check the type of the field
                                                if (!ClassUtils.typesAreCompatible(fieldType, resultFieldType) && !ClassUtils.typesAreCompatible(resultFieldType, fieldType))
                                                {
                                                    throw new NucleusUserException(Localiser.msg("021211", fieldName, fieldType.getName(), resultFieldType.getName()));
                                                }
                                                if (!Modifier.isPublic(fld.getModifiers()))
                                                {
                                                    publicField = false;
                                                }
                                            }
                                            catch (NoSuchFieldException nsfe)
                                            {
                                                publicField = false;
                                            }

                                            // Check for a public set method
                                            if (!publicField)
                                            {
                                                Method setMethod = QueryUtils.getPublicSetMethodForFieldOfResultClass(resultClass, fieldName, resultFieldType);
                                                if (setMethod == null)
                                                {
                                                    // No setter, so check for a public put(Object, Object) method
                                                    Method putMethod = QueryUtils.getPublicPutMethodForResultClass(resultClass);
                                                    if (putMethod == null)
                                                    {
                                                        throw new NucleusUserException(Localiser.msg("021212", resultClass.getName(), fieldName));
                                                    }
                                                }
                                            }
                                        }
                                    }
                                    else if (stmtMap instanceof StatementNewObjectMapping)
                                    {
                                        // TODO Handle this
                                    }
                                }
                            }
                        }

                        return null;
                    }
                });
            }

            boolean hasParams = false;
            if (explicitParameters != null)
            {
                hasParams = true;
            }
            else if (parameterValues != null && parameterValues.size() > 0)
            {
                hasParams = true;
            }
            if (!datastoreCompilation.isPrecompilable() || (datastoreCompilation.getSQL().indexOf('?') < 0 && hasParams))
            {
                // Some parameters had their clauses evaluated during compilation so the query didn't gain any parameters, so don't cache it
                NucleusLogger.QUERY.debug(Localiser.msg("021075"));
            }
            else
            {
                if (!statementReturnsEmpty && useCaching() && queryCacheKey != null)
                {
                	qm.addDatastoreQueryCompilation(datastoreKey, getLanguage(), queryCacheKey, datastoreCompilation);
                }
            }
        }
    }

    /**
     * Convenience accessor for the SQL to invoke in the datastore for this query.
     * @return The SQL.
     */
    public String getSQL()
    {
        if (datastoreCompilation != null)
        {
            return datastoreCompilation.getSQL();
        }
        return null;
    }

    protected Object performExecute(Map parameters)
    {
        if (statementReturnsEmpty)
        {
            return Collections.EMPTY_LIST;
        }

        if (candidateCollection != null)
        {
            // Supplied collection of instances, so evaluate in-memory
            if (candidateCollection.isEmpty())
            {
                return Collections.EMPTY_LIST;
            }

            List candidates = new ArrayList(candidateCollection);
            return new JPQLInMemoryEvaluator(this, candidates, compilation, parameters, clr).execute(true, true, true, true, true);
        }
        else if (type == QueryType.SELECT)
        {
            // Query results are cached, so return those
            List<Object> cachedResults = getQueryManager().getQueryResult(this, parameters);
            if (cachedResults != null)
            {
                return new CandidateIdsQueryResult(this, cachedResults);
            }
        }

        Object results = null;
        ManagedConnection mconn = getStoreManager().getConnectionManager().getConnection(ec);
        try
        {
            // Execute the query
            long startTime = System.currentTimeMillis();
            if (NucleusLogger.QUERY.isDebugEnabled())
            {
                NucleusLogger.QUERY.debug(Localiser.msg("021046", getLanguage(), getSingleStringQuery(), null));
            }

            RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
            AbstractClassMetaData acmd = ec.getMetaDataManager().getMetaDataForClass(candidateClass, clr);
            SQLController sqlControl = storeMgr.getSQLController();
            PreparedStatement ps = null;
            try
            {
                if (type == QueryType.SELECT)
                {
                    // Create PreparedStatement and apply parameters, result settings etc
                    ps = RDBMSQueryUtils.getPreparedStatementForQuery(mconn, datastoreCompilation.getSQL(), this);
                    SQLStatementHelper.applyParametersToStatement(ps, ec, datastoreCompilation.getStatementParameters(), null, parameters);
                    RDBMSQueryUtils.prepareStatementForExecution(ps, this, true);

                    registerTask(ps);
                    ResultSet rs = null;
                    try
                    {
                        rs = sqlControl.executeStatementQuery(ec, mconn, toString(), ps);
                    }
                    finally
                    {
                        deregisterTask();
                    }

                    AbstractRDBMSQueryResult qr = null;
                    try
                    {
                        if (evaluateInMemory())
                        {
                            // IN-MEMORY EVALUATION
                            ResultObjectFactory rof = new PersistentClassROF(ec, rs, ignoreCache, getFetchPlan(), datastoreCompilation.getResultDefinitionForClass(), acmd, candidateClass);

                            // Just instantiate the candidates for later in-memory processing
                            // TODO Use a queryResult rather than an ArrayList so we load when required
                            List candidates = new ArrayList();
                            while (rs.next())
                            {
                                candidates.add(rof.getObject());
                            }

                            // Perform in-memory filter/result/order etc
                            results = new JPQLInMemoryEvaluator(this, candidates, compilation, parameters, clr).execute(true, true, true, true, true);
                        }
                        else
                        {
                            // IN-DATASTORE EVALUATION
                            ResultObjectFactory rof = null;
                            if (result != null)
                            {
                                // Each result row is of a result type
                                rof = new ResultClassROF(ec, rs, ignoreCache, getFetchPlan(), resultClass, datastoreCompilation.getResultDefinition());
                            }
                            else if (resultClass != null && resultClass != candidateClass)
                            {
                                rof = new ResultClassROF(ec, rs, ignoreCache, getFetchPlan(), resultClass, datastoreCompilation.getResultDefinitionForClass());
                            }
                            else
                            {
                                // Each result row is a candidate object
                                rof = new PersistentClassROF(ec, rs, ignoreCache, getFetchPlan(), datastoreCompilation.getResultDefinitionForClass(), acmd, candidateClass);
                            }

                            // Create the required type of QueryResult
                            qr = RDBMSQueryUtils.getQueryResultForQuery(this, rof, rs, getResultDistinct() ? null : candidateCollection);

                            // Register any bulk loaded member resultSets that need loading
                            Map<String, IteratorStatement> scoIterStmts = datastoreCompilation.getSCOIteratorStatements();
                            if (scoIterStmts != null)
                            {
                                Iterator<Map.Entry<String, IteratorStatement>> scoStmtIter = scoIterStmts.entrySet().iterator();
                                while (scoStmtIter.hasNext())
                                {
                                    Map.Entry<String, IteratorStatement> stmtIterEntry = scoStmtIter.next();
                                    IteratorStatement iterStmt = stmtIterEntry.getValue();
                                    String iterStmtSQL = iterStmt.getSelectStatement().getSQLText().toSQL();
                                    NucleusLogger.DATASTORE_RETRIEVE.debug("JPQL Bulk-Fetch of " + iterStmt.getBackingStore().getOwnerMemberMetaData().getFullFieldName());
                                    try
                                    {
                                        PreparedStatement psSco = sqlControl.getStatementForQuery(mconn, iterStmtSQL);
                                        if (datastoreCompilation.getStatementParameters() != null)
                                        {
                                            BulkFetchHandler.applyParametersToStatement(ec, psSco, datastoreCompilation, iterStmt.getSelectStatement(), parameters);
                                        }
                                        ResultSet rsSCO = sqlControl.executeStatementQuery(ec, mconn, iterStmtSQL, psSco);
                                        qr.registerMemberBulkResultSet(iterStmt, rsSCO);
                                    }
                                    catch (SQLException e)
                                    {
                                        throw new NucleusDataStoreException(Localiser.msg("056006", iterStmtSQL), e);
                                    }
                                }
                            }

                            qr.initialise();

                            final QueryResult qr1 = qr;
                            final ManagedConnection mconn1 = mconn;
                            ManagedConnectionResourceListener listener = new ManagedConnectionResourceListener()
                            {
                                public void transactionFlushed(){}
                                public void transactionPreClose()
                                {
                                    // Tx : disconnect query from ManagedConnection (read in unread rows etc)
                                    qr1.disconnect();
                                }
                                public void managedConnectionPreClose()
                                {
                                    if (!ec.getTransaction().isActive())
                                    {
                                        // Non-Tx : disconnect query from ManagedConnection (read in unread rows etc)
                                        qr1.disconnect();
                                    }
                                }
                                public void managedConnectionPostClose(){}
                                public void resourcePostClose()
                                {
                                    mconn1.removeListener(this);
                                }
                            };
                            mconn.addListener(listener);
                            qr.addConnectionListener(listener);
                            results = qr;
                        }
                    }
                    finally
                    {
                        if (qr == null)
                        {
                            rs.close();
                        }
                    }
                }
                else if (type == QueryType.BULK_UPDATE || type == QueryType.BULK_DELETE || type == QueryType.BULK_INSERT)
                {
                    long bulkResult = 0;
                    List<StatementCompilation> stmtCompilations = datastoreCompilation.getStatementCompilations();
                    Iterator<StatementCompilation> stmtCompileIter = stmtCompilations.iterator();
                    while (stmtCompileIter.hasNext())
                    {
                        StatementCompilation stmtCompile = stmtCompileIter.next();
                        ps = sqlControl.getStatementForUpdate(mconn, stmtCompile.getSQL(), false);
                        SQLStatementHelper.applyParametersToStatement(ps, ec, datastoreCompilation.getStatementParameters(), null, parameters);
                        RDBMSQueryUtils.prepareStatementForExecution(ps, this, false);

                        int[] execResults = sqlControl.executeStatementUpdate(ec, mconn, toString(), ps, true);
                        if (stmtCompile.useInCount())
                        {
                            bulkResult += execResults[0];
                        }
                    }

                    try
                    {
                        // Evict all objects of this type from the cache
                        ec.getNucleusContext().getLevel2Cache().evictAll(candidateClass, subclasses);
                    }
                    catch (UnsupportedOperationException uoe)
                    {
                        // Do nothing
                    }

                    results = bulkResult;
                }
            }
            catch (SQLException sqle)
            {
                if (storeMgr.getDatastoreAdapter().isStatementCancel(sqle))
                {
                    throw new QueryInterruptedException("Query has been interrupted", sqle);
                }
                else if (storeMgr.getDatastoreAdapter().isStatementTimeout(sqle))
                {
                    throw new QueryTimeoutException("Query has been timed out", sqle);
                }
                throw new NucleusException(Localiser.msg("021042", datastoreCompilation.getSQL()), sqle);
            }

            if (NucleusLogger.QUERY.isDebugEnabled())
            {
                NucleusLogger.QUERY.debug(Localiser.msg("021074", getLanguage(), "" + (System.currentTimeMillis() - startTime)));
            }

            return results;
        }
        finally
        {
            mconn.release();
        }
    }

    /**
     * Method that will throw an {@link UnsupportedOperationException} if the query implementation doesn't
     * support cancelling queries.
     */
    protected void assertSupportsCancel()
    {
        // We support cancel via JDBC PreparedStatement.cancel();
    }

    protected boolean cancelTaskObject(Object obj)
    {
        Statement ps = (Statement)obj;
        try
        {
            ps.cancel();
            return true;
        }
        catch (SQLException sqle)
        {
            NucleusLogger.DATASTORE_RETRIEVE.warn("Error cancelling query : " + StringUtils.getMessageFromRootCauseOfThrowable(sqle));
            return false;
        }
    }

    /**
     * Convenience method for whether this query supports timeouts.
     * @return Whether timeouts are supported.
     */
    protected boolean supportsTimeout()
    {
        return true;
    }

    /**
     * Method to set the (native) query statement for the compiled query as a whole.
     * The "table groups" in the resultant SQLStatement will be named as per the candidate alias,
     * and thereafter "{alias}.{fieldName}". 
     * @param parameters Input parameters (if known)
     * @param candidateCmd Metadata for the candidate class
     */
    private void compileQueryFull(Map parameters, AbstractClassMetaData candidateCmd)
    {
        if (type != QueryType.SELECT)
        {
            return;
        }
        if (candidateCollection != null)
        {
            return;
        }

        long startTime = 0;
        if (NucleusLogger.QUERY.isDebugEnabled())
        {
            startTime = System.currentTimeMillis();
            NucleusLogger.QUERY.debug(Localiser.msg("021083", getLanguage(), toString()));
        }

        if (result != null)
        {
            datastoreCompilation.setResultDefinition(new StatementResultMapping());
        }
        else
        {
            datastoreCompilation.setResultDefinitionForClass(new StatementClassMapping());
        }

        // Generate statement for candidate(s)
        SelectStatement stmt = null;
        try
        {
            boolean includeSoftDeletes = getBooleanExtensionProperty(EXTENSION_INCLUDE_SOFT_DELETES, false);
            boolean dontRestrictDiscrim = getBooleanExtensionProperty(EXTENSION_CANDIDATE_DONT_RESTRICT_DISCRIMINATOR, false);
            Set<String> options = null;
            if (includeSoftDeletes)
            {
                options = new HashSet<>();
                options.add(SelectStatementGenerator.OPTION_INCLUDE_SOFT_DELETES);
            }
            if (dontRestrictDiscrim)
            {
                if (options == null)
                {
                    options = new HashSet<>();
                }
                options.add(SelectStatementGenerator.OPTION_DONT_RESTRICT_DISCRIM);
            }
            stmt = RDBMSQueryUtils.getStatementForCandidates((RDBMSStoreManager) getStoreManager(), null, candidateCmd,
                datastoreCompilation.getResultDefinitionForClass(), ec, candidateClass, subclasses, result, 
                compilation.getCandidateAlias(), compilation.getCandidateAlias(), options);
        }
        catch (NucleusException ne)
        {
            // Statement would result in no results, so just catch it and avoid generating the statement
            NucleusLogger.QUERY.warn("Query for candidates of " + candidateClass.getName() +
                (subclasses ? " and subclasses" : "") + " resulted in no possible candidates : " + StringUtils.getMessageFromRootCauseOfThrowable(ne));
            statementReturnsEmpty = true;
            return;
        }

        // Update the SQLStatement with filter, ordering, result etc
        Set<String> options = new HashSet<>();
        options.add(QueryToSQLMapper.OPTION_CASE_INSENSITIVE);
        options.add(QueryToSQLMapper.OPTION_EXPLICIT_JOINS);
        if (getBooleanExtensionProperty(EXTENSION_USE_IS_NULL_WHEN_EQUALS_NULL_PARAM, false)) // Default to false for "IS NULL" with null param
        {
            options.add(QueryToSQLMapper.OPTION_NULL_PARAM_USE_IS_NULL);
        }
        QueryToSQLMapper sqlMapper = new QueryToSQLMapper(stmt, compilation, parameters, datastoreCompilation.getResultDefinitionForClass(), datastoreCompilation.getResultDefinition(),
            candidateCmd, subclasses, getFetchPlan(), ec, null, options, extensions);
        setMapperJoinTypes(sqlMapper);
        sqlMapper.compile();

        datastoreCompilation.setParameterNameByPosition(sqlMapper.getParameterNameByPosition());
        datastoreCompilation.setPrecompilable(sqlMapper.isPrecompilable());

        // Apply any range
        if (range != null)
        {
            long lower = fromInclNo;
            long upper = toExclNo;
            if (fromInclParam != null)
            {
                lower = ((Number)parameters.get(fromInclParam)).longValue();
            }
            if (toExclParam != null)
            {
                upper = ((Number)parameters.get(toExclParam)).longValue();
            }
            long count = upper - lower;
            if (upper == Long.MAX_VALUE)
            {
                count = -1;
            }
            stmt.setRange(lower, count);
        }

        // Set any extensions
        boolean useUpdateLock = RDBMSQueryUtils.useUpdateLockForQuery(this);
        stmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE, Boolean.valueOf(useUpdateLock));
        if (getBooleanExtensionProperty(EXTENSION_FOR_UPDATE_NOWAIT, false))
        {
            stmt.addExtension(SQLStatement.EXTENSION_LOCK_FOR_UPDATE_NOWAIT, Boolean.TRUE);
        }

        datastoreCompilation.addStatement(stmt, stmt.getSQLText().toSQL(), false);
        datastoreCompilation.setStatementParameters(stmt.getSQLText().getParametersForStatement());

        if (result == null && !(resultClass != null && resultClass != candidateClass))
        {
            // Select of candidates, so check for any immediate multi-valued fields that are marked for fetching
            // TODO If the query joins to a 1-1/N-1 and then we have a multi-valued field, we should allow that too
            FetchPlanForClass fpc = getFetchPlan().getFetchPlanForClass(candidateCmd);
            int[] fpMembers = fpc.getMemberNumbers();
            String multifetchType = getStringExtensionProperty(RDBMSPropertyNames.PROPERTY_RDBMS_QUERY_MULTIVALUED_FETCH, null);
            if ("none".equalsIgnoreCase(multifetchType))
            {
            }
            else
            {
                // Bulk-Fetch
                for (int i=0;i<fpMembers.length;i++)
                {
                    AbstractMemberMetaData fpMmd = candidateCmd.getMetaDataForManagedMemberAtAbsolutePosition(fpMembers[i]);

                    if (multifetchType == null)
                    {
                        // Default to bulk-fetch, so advise the user of why this is happening and how to turn it off
                        NucleusLogger.QUERY.debug("You have selected field " + fpMmd.getFullFieldName() + " for fetching by this query. We will fetch it using 'EXISTS'." +
                                " To disable this set the query extension/hint '" + RDBMSPropertyNames.PROPERTY_RDBMS_QUERY_MULTIVALUED_FETCH + "' as 'none' or remove the field" +
                                " from the query FetchPlan. If this bulk-fetch generates an invalid or unoptimised query, please report it with a way of reproducing it");
                        multifetchType = "exists";
                    }

                    RelationType relType = fpMmd.getRelationType(clr);
                    if (RelationType.isRelationMultiValued(relType))
                    {
                        if (fpMmd.hasCollection() && SCOUtils.collectionHasSerialisedElements(fpMmd))
                        {
                            // Ignore collections stored into the owner (retrieved in main query)
                        }
                        else if (fpMmd.hasArray() && SCOUtils.arrayIsStoredInSingleColumn(fpMmd, ec.getMetaDataManager()))
                        {
                            // Ignore arrays stored into the owner (retrieved in main query)
                        }
                        else if (fpMmd.hasMap() && SCOUtils.mapHasSerialisedKeysAndValues(fpMmd))
                        {
                            // Ignore maps stored into the owner (retrieved in main query)
                        }
                        else
                        {
                            if ("exists".equalsIgnoreCase(multifetchType))
                            {
                                // Fetch container contents for all candidate owners
                                BulkFetchExistsHandler helper = new BulkFetchExistsHandler();
                                IteratorStatement iterStmt = helper.getStatementToBulkFetchField(candidateCmd, fpMmd, this, parameters, datastoreCompilation, options);
                                if (iterStmt != null)
                                {
                                    datastoreCompilation.setSCOIteratorStatement(fpMmd.getFullFieldName(), iterStmt);
                                }
                                else
                                {
                                    NucleusLogger.GENERAL.debug("Query has field " + fpMmd.getFullFieldName() + " marked in the FetchPlan, yet this is currently not (bulk) fetched by this query");
                                }
                            }
                            else if ("join".equalsIgnoreCase(multifetchType))
                            {
                                // Fetch container contents for all candidate owners
                                BulkFetchJoinHandler helper = new BulkFetchJoinHandler();
                                IteratorStatement iterStmt = helper.getStatementToBulkFetchField(candidateCmd, fpMmd, this, parameters, datastoreCompilation, options);
                                if (iterStmt != null)
                                {
                                    datastoreCompilation.setSCOIteratorStatement(fpMmd.getFullFieldName(), iterStmt);
                                }
                                else
                                {
                                    NucleusLogger.GENERAL.debug("Query has field " + fpMmd.getFullFieldName() + " marked in the FetchPlan, yet this is currently not (bulk) fetched by this query");
                                }
                            }
                            else
                            {
                                NucleusLogger.GENERAL.debug("Query has field " + fpMmd.getFullFieldName() + " marked in the FetchPlan, yet this is not (bulk) fetched by this query; unsupported bulk-fetch type.");
                            }
                        }
                    }
                }
            }
        }

        if (NucleusLogger.QUERY.isDebugEnabled())
        {
            NucleusLogger.QUERY.debug(Localiser.msg("021084", getLanguage(), System.currentTimeMillis()-startTime));
        }
    }

    /**
     * Method to set the statement (and parameter/results definitions) to retrieve all candidates.
     * This is used when we want to evaluate in-memory and so just retrieve all possible candidates
     * first.
     * @param parameters Input parameters (if known)
     * @param candidateCmd Metadata for the candidate class
     */
    private void compileQueryToRetrieveCandidates(Map parameters, AbstractClassMetaData candidateCmd)
    {
        if (type != QueryType.SELECT)
        {
            return;
        }
        if (candidateCollection != null)
        {
            return;
        }

        StatementClassMapping resultsDef = new StatementClassMapping();
        datastoreCompilation.setResultDefinitionForClass(resultsDef);

        // Generate statement for candidate(s)
        SelectStatement stmt = null;
        try
        {
            stmt = RDBMSQueryUtils.getStatementForCandidates((RDBMSStoreManager) getStoreManager(), null, candidateCmd,
                datastoreCompilation.getResultDefinitionForClass(), ec, candidateClass, subclasses, result, null, null, null);
        }
        catch (NucleusException ne)
        {
            // Statement would result in no results, so just catch it and avoid generating the statement
            NucleusLogger.QUERY.warn("Query for candidates of " + candidateClass.getName() + (subclasses ? " and subclasses" : "") + " resulted in no possible candidates", ne);
            statementReturnsEmpty = true;
            return;
        }

        if (stmt.allUnionsForSamePrimaryTable())
        {
            // Select fetch-plan fields of candidate class
            SQLStatementHelper.selectFetchPlanOfCandidateInStatement(stmt, datastoreCompilation.getResultDefinitionForClass(), candidateCmd, getFetchPlan(), 1);
        }
        else
        {
            // Select id only since tables don't have same mappings or column names
            // TODO complete-table will come through here but maybe ought to be treated differently
            SQLStatementHelper.selectIdentityOfCandidateInStatement(stmt, datastoreCompilation.getResultDefinitionForClass(), candidateCmd);
        }

        datastoreCompilation.addStatement(stmt, stmt.getSQLText().toSQL(), false);
        datastoreCompilation.setStatementParameters(stmt.getSQLText().getParametersForStatement());
    }

    /**
     * Method to compile the query for RDBMS for a bulk INSERT.
     * @param parameterValues The parameter values (if any)
     * @param candidateCmd Meta-data for the candidate class
     */
    protected void compileQueryInsert(Map parameterValues, AbstractClassMetaData candidateCmd)
    {
        if (StringUtils.isWhitespace(insertFields) || StringUtils.isWhitespace(insertSelectQuery))
        {
            // Nothing to INSERT
            return;
        }

        List<String> fieldNames = new ArrayList<>();
        StringTokenizer fieldTokenizer = new StringTokenizer(insertFields, ",");
        while (fieldTokenizer.hasMoreTokens())
        {
            String token = fieldTokenizer.nextToken().trim();
            fieldNames.add(token);
        }

        // Generate statement for candidate and related classes in this inheritance tree
        RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
        DatastoreClass candidateTbl = storeMgr.getDatastoreClass(candidateCmd.getFullClassName(), clr);
        if (candidateTbl == null)
        {
            // TODO Using subclass-table, so find the table(s) it can be persisted into
            throw new NucleusDataStoreException("Bulk INSERT of " + candidateCmd.getFullClassName() + " not supported since candidate has no table of its own");
        }

        // Find table(s) that need populating with this information
        List<BulkTable> tables = new ArrayList<>();
        tables.add(new BulkTable(candidateTbl, true));
        if (candidateTbl.getSuperDatastoreClass() != null)
        {
            DatastoreClass tbl = candidateTbl;
            while (tbl.getSuperDatastoreClass() != null)
            {
                tbl = tbl.getSuperDatastoreClass();
                tables.add(0, new BulkTable(tbl, false));
            }
        }
        if (tables.size() > 1)
        {
            throw new NucleusUserException("BULK INSERT only currently allows a single table, but this query implies INSERT into " + tables.size() + " tables!");
        }

        List<SQLStatement> stmts = new ArrayList<>();
        List<Boolean> stmtCountFlags = new ArrayList<>();
        for (BulkTable bulkTable : tables)
        {
            // Generate statement for candidate
            InsertStatement stmt = new InsertStatement(storeMgr, bulkTable.table, null, null, null);
            stmt.setClassLoaderResolver(clr);
            stmt.setCandidateClassName(candidateCmd.getFullClassName());

            // Set columns for this table
            for (String fieldName : fieldNames)
            {
                AbstractMemberMetaData fieldMmd = candidateCmd.getMetaDataForMember(fieldName);
                if (fieldMmd == null)
                {
                    // No such field
                }
                else
                {
                    JavaTypeMapping fieldMapping = bulkTable.table.getMemberMapping(fieldMmd);
                    if (fieldMapping != null)
                    {
                        SQLExpression fieldExpr = stmt.getSQLExpressionFactory().newExpression(stmt, stmt.getPrimaryTable(), fieldMapping);
                        for (int i=0;i<fieldExpr.getNumberOfSubExpressions();i++)
                        {
                            ColumnExpression fieldColExpr = fieldExpr.getSubExpression(i);
                            fieldColExpr.setOmitTableFromString(true);
                        }
                        stmt.addColumn(fieldExpr);
                    }
                    else
                    {
                        // Not in this table
                    }
                }
            }

            // Generate the select query and add it to the InsertStatement
            JPQLQuery selectQuery = new JPQLQuery(storeMgr, ec, insertSelectQuery);
            selectQuery.compile();
            stmt.setSelectStatement((SelectStatement) selectQuery.getDatastoreCompilation().getStatementCompilations().get(0).getStatement());
            selectQuery.closeAll();

            // TODO if we have multiple tables then this will mean only using some of the columns in the selectSQL
            stmts.add(stmt);
            stmtCountFlags.add(bulkTable.useInCount);

            datastoreCompilation.setStatementParameters(stmt.getSQLText().getParametersForStatement());
        }

        datastoreCompilation.clearStatements();
        Iterator<SQLStatement> stmtIter = stmts.iterator();
        Iterator<Boolean> stmtCountFlagsIter = stmtCountFlags.iterator();
        while (stmtIter.hasNext())
        {
            SQLStatement stmt = stmtIter.next();
            Boolean useInCount = stmtCountFlagsIter.next();
            if (stmts.size() == 1)
            {
                useInCount = true;
            }
            datastoreCompilation.addStatement(stmt, stmt.getSQLText().toSQL(), useInCount);
        }
    }

    /**
     * Method to compile the query for RDBMS for a bulk update.
     * @param parameterValues The parameter values (if any)
     * @param candidateCmd Meta-data for the candidate class
     */
    protected void compileQueryUpdate(Map parameterValues, AbstractClassMetaData candidateCmd)
    {
        Expression[] updateExprs = compilation.getExprUpdate();
        if (updateExprs == null || updateExprs.length == 0)
        {
            // Nothing to update
            return;
        }

        // Generate statement for candidate and related classes in this inheritance tree
        RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
        DatastoreClass candidateTbl = storeMgr.getDatastoreClass(candidateCmd.getFullClassName(), clr);
        if (candidateTbl == null)
        {
            // TODO Using subclass-table, so find the table(s) it can be persisted into
            throw new NucleusDataStoreException("Bulk update of " + candidateCmd.getFullClassName() + " not supported since candidate has no table of its own");
        }

        // Find tables potentially affected by this UPDATE statement
        List<BulkTable> tables = new ArrayList<>();
        tables.add(new BulkTable(candidateTbl, true));
        if (candidateTbl.getSuperDatastoreClass() != null)
        {
            DatastoreClass tbl = candidateTbl;
            while (tbl.getSuperDatastoreClass() != null)
            {
                tbl = tbl.getSuperDatastoreClass();
                tables.add(new BulkTable(tbl, false));
            }
        }

        List<SQLStatement> stmts = new ArrayList<>();
        List<Boolean> stmtCountFlags = new ArrayList<>();
        for (BulkTable bulkTable : tables)
        {
            // Generate statement for candidate
            DatastoreClass table = bulkTable.table;
            Map<String, Object> extensions = null;
            if (!storeMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.UPDATE_DELETE_STATEMENT_ALLOW_TABLE_ALIAS_IN_WHERE_CLAUSE))
            {
                extensions = new HashMap<>();
                extensions.put(SQLStatement.EXTENSION_SQL_TABLE_NAMING_STRATEGY, "table-name");
            }
            UpdateStatement stmt = new UpdateStatement(storeMgr, table, null, null, extensions);
            stmt.setClassLoaderResolver(clr);
            stmt.setCandidateClassName(candidateCmd.getFullClassName());

            JavaTypeMapping multitenancyMapping = table.getSurrogateMapping(SurrogateColumnType.MULTITENANCY, false);
            if (multitenancyMapping != null)
            {
                // Multi-tenancy restriction
                SQLExpression tenantExpr = stmt.getSQLExpressionFactory().newExpression(stmt, stmt.getPrimaryTable(), multitenancyMapping);
                SQLExpression tenantVal = stmt.getSQLExpressionFactory().newLiteral(stmt, multitenancyMapping, ec.getNucleusContext().getMultiTenancyId(ec));
                stmt.whereAnd(tenantExpr.eq(tenantVal), true);
            }

            // TODO Discriminator restriction?

            JavaTypeMapping softDeleteMapping = table.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
            if (softDeleteMapping != null)
            {
                // Soft-delete restriction
                SQLExpression softDeleteExpr = stmt.getSQLExpressionFactory().newExpression(stmt, stmt.getPrimaryTable(), softDeleteMapping);
                SQLExpression softDeleteVal = stmt.getSQLExpressionFactory().newLiteral(stmt, softDeleteMapping, Boolean.FALSE);
                stmt.whereAnd(softDeleteExpr.eq(softDeleteVal), true);
            }

            Set<String> options = new HashSet<>();
            options.add(QueryToSQLMapper.OPTION_CASE_INSENSITIVE);
            options.add(QueryToSQLMapper.OPTION_EXPLICIT_JOINS);
            if (getBooleanExtensionProperty(EXTENSION_USE_IS_NULL_WHEN_EQUALS_NULL_PARAM, false)) // Default to false for "IS NULL" with null param
            {
                options.add(QueryToSQLMapper.OPTION_NULL_PARAM_USE_IS_NULL);
            }
            QueryToSQLMapper sqlMapper = new QueryToSQLMapper(stmt, compilation, parameterValues, null, null, candidateCmd, subclasses, getFetchPlan(), ec, null, options, extensions);
            setMapperJoinTypes(sqlMapper);
            sqlMapper.compile();

            if (stmt.hasUpdates())
            {
                stmts.add(stmt);
                stmtCountFlags.add(bulkTable.useInCount);

                datastoreCompilation.setStatementParameters(stmt.getSQLText().getParametersForStatement());
                datastoreCompilation.setPrecompilable(sqlMapper.isPrecompilable());
            }
        }

        datastoreCompilation.clearStatements();
        Iterator<SQLStatement> stmtIter = stmts.iterator();
        Iterator<Boolean> stmtCountFlagsIter = stmtCountFlags.iterator();
        while (stmtIter.hasNext())
        {
            SQLStatement stmt = stmtIter.next();
            Boolean useInCount = stmtCountFlagsIter.next();
            if (stmts.size() == 1)
            {
                useInCount = true;
            }
            datastoreCompilation.addStatement(stmt, stmt.getSQLText().toSQL(), useInCount);
        }
    }

    private class BulkTable
    {
        DatastoreClass table;
        boolean useInCount;
        public BulkTable(DatastoreClass tbl, boolean useInCount)
        {
            this.table = tbl;
            this.useInCount = useInCount;
        }
        public String toString() { return table.toString(); }
    }

    /**
     * Method to compile the query for RDBMS for a bulk delete.
     * @param parameterValues The parameter values (if any)
     * @param candidateCmd Meta-data for the candidate class
     */
    protected void compileQueryDelete(Map parameterValues, AbstractClassMetaData candidateCmd)
    {
        RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
        DatastoreClass candidateTbl = storeMgr.getDatastoreClass(candidateCmd.getFullClassName(), clr);
        if (candidateTbl == null)
        {
            // TODO Using subclass-table, so find the table(s) it can be persisted into
            throw new NucleusDataStoreException("Bulk delete of " + candidateCmd.getFullClassName() + " not supported since candidate has no table of its own");
        }

        InheritanceStrategy inhStr = candidateCmd.getBaseAbstractClassMetaData().getInheritanceMetaData().getStrategy();

        List<BulkTable> tables = new ArrayList<>();
        tables.add(new BulkTable(candidateTbl, true));
        if (inhStr != InheritanceStrategy.COMPLETE_TABLE)
        {
            // Add deletion from superclass tables since we will have an entry there
            while (candidateTbl.getSuperDatastoreClass() != null)
            {
                candidateTbl = candidateTbl.getSuperDatastoreClass();
                tables.add(new BulkTable(candidateTbl, false));
            }
        }

        Collection<String> subclassNames = storeMgr.getSubClassesForClass(candidateCmd.getFullClassName(), true, clr);
        if (subclassNames != null && !subclassNames.isEmpty())
        {
            // Check for subclasses having their own tables and hence needing multiple DELETEs
            Iterator<String> iter = subclassNames.iterator();
            while (iter.hasNext())
            {
                String subclassName = iter.next();
                DatastoreClass subclassTbl = storeMgr.getDatastoreClass(subclassName, clr);
                if (candidateTbl != subclassTbl)
                {
                    // Only include BulkTable in count if using COMPLETE_TABLE strategy
                    tables.add(0, new BulkTable(subclassTbl, inhStr == InheritanceStrategy.COMPLETE_TABLE));
                }
            }
        }

        List<SQLStatement> stmts = new ArrayList<>();
        List<Boolean> stmtCountFlags = new ArrayList<>();
        for (BulkTable bulkTable : tables)
        {
            // Generate statement for candidate
            DatastoreClass table = bulkTable.table;
            JavaTypeMapping softDeleteMapping = table.getSurrogateMapping(SurrogateColumnType.SOFTDELETE, false);
            if (softDeleteMapping != null)
            {
                throw new NucleusUserException("Cannot use BulkDelete queries when using SoftDelete on an affected table (" + table + ")");
            }

            Map<String, Object> extensions = null;
            if (!storeMgr.getDatastoreAdapter().supportsOption(DatastoreAdapter.UPDATE_DELETE_STATEMENT_ALLOW_TABLE_ALIAS_IN_WHERE_CLAUSE))
            {
                extensions = new HashMap<>();
                extensions.put(SQLStatement.EXTENSION_SQL_TABLE_NAMING_STRATEGY, "table-name");
            }
            SQLStatement stmt = new DeleteStatement(storeMgr, table, null, null, extensions);
            stmt.setClassLoaderResolver(clr);
            stmt.setCandidateClassName(candidateCmd.getFullClassName());

            JavaTypeMapping multitenancyMapping = table.getSurrogateMapping(SurrogateColumnType.MULTITENANCY, false);
            if (multitenancyMapping != null)
            {
                // Multi-tenancy restriction
                SQLTable tenantSqlTbl = stmt.getPrimaryTable();
                SQLExpression tenantExpr = stmt.getSQLExpressionFactory().newExpression(stmt, tenantSqlTbl, multitenancyMapping);
                SQLExpression tenantVal = stmt.getSQLExpressionFactory().newLiteral(stmt, multitenancyMapping, ec.getNucleusContext().getMultiTenancyId(ec));
                stmt.whereAnd(tenantExpr.eq(tenantVal), true);
            }
            // TODO Discriminator restriction?

            Set<String> options = new HashSet<>();
            options.add(QueryToSQLMapper.OPTION_CASE_INSENSITIVE);
            options.add(QueryToSQLMapper.OPTION_EXPLICIT_JOINS);
            if (getBooleanExtensionProperty(EXTENSION_USE_IS_NULL_WHEN_EQUALS_NULL_PARAM, false)) // Default to false for "IS NULL" with null param
            {
                options.add(QueryToSQLMapper.OPTION_NULL_PARAM_USE_IS_NULL);
            }
            QueryToSQLMapper sqlMapper = new QueryToSQLMapper(stmt, compilation, parameterValues, null, null, candidateCmd, subclasses, getFetchPlan(), ec, null, options, extensions);
            setMapperJoinTypes(sqlMapper);
            sqlMapper.compile();

            stmts.add(stmt);
            stmtCountFlags.add(bulkTable.useInCount);

            datastoreCompilation.setStatementParameters(stmt.getSQLText().getParametersForStatement());
            datastoreCompilation.setPrecompilable(sqlMapper.isPrecompilable());
        }

        datastoreCompilation.clearStatements();
        Iterator<SQLStatement> stmtIter = stmts.iterator();
        Iterator<Boolean> stmtCountFlagsIter = stmtCountFlags.iterator();
        while (stmtIter.hasNext())
        {
            SQLStatement stmt = stmtIter.next();
            Boolean useInCount = stmtCountFlagsIter.next();
            if (stmts.size() == 1)
            {
                useInCount = true;
            }
            datastoreCompilation.addStatement(stmt, stmt.getSQLText().toSQL(), useInCount);
        }
    }

    private void setMapperJoinTypes(QueryToSQLMapper sqlMapper)
    {
        String defaultJoinTypeFilter = getStringExtensionProperty(EXTENSION_NAVIGATION_JOIN_TYPE_FILTER, null);
        if (defaultJoinTypeFilter != null)
        {
            if (defaultJoinTypeFilter.equalsIgnoreCase("INNERJOIN"))
            {
                sqlMapper.setDefaultJoinTypeFilter(JoinType.INNER_JOIN);
            }
            else if (defaultJoinTypeFilter.equalsIgnoreCase("LEFTOUTERJOIN"))
            {
                sqlMapper.setDefaultJoinTypeFilter(JoinType.LEFT_OUTER_JOIN);
            }
        }
        else
        {
            sqlMapper.setDefaultJoinTypeFilter(JoinType.INNER_JOIN);
        }

        String defaultJoinType = getStringExtensionProperty(EXTENSION_NAVIGATION_JOIN_TYPE, null);
        if (defaultJoinType != null)
        {
            if (defaultJoinType.equalsIgnoreCase("INNERJOIN"))
            {
                sqlMapper.setDefaultJoinType(JoinType.INNER_JOIN);
            }
            else if (defaultJoinType.equalsIgnoreCase("LEFTOUTERJOIN"))
            {
                sqlMapper.setDefaultJoinType(JoinType.LEFT_OUTER_JOIN);
            }
        }
        else
        {
            sqlMapper.setDefaultJoinType(JoinType.INNER_JOIN);
        }
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.query.Query#processesRangeInDatastoreQuery()
     */
    @Override
    public boolean processesRangeInDatastoreQuery()
    {
        if (range == null)
        {
            // No range specified so makes no difference
            return true;
        }

        RDBMSStoreManager storeMgr = (RDBMSStoreManager)getStoreManager();
        DatastoreAdapter dba = storeMgr.getDatastoreAdapter();
        boolean using_limit_where_clause = (dba.getRangeByLimitEndOfStatementClause(fromInclNo, toExclNo, !StringUtils.isWhitespace(ordering)).length() > 0);
        boolean using_rownum = (dba.getRangeByRowNumberColumn().length() > 0) || (dba.getRangeByRowNumberColumn2().length() > 0);

        return using_limit_where_clause || using_rownum;
    }

    /**
     * Method to return the names of the extensions supported by this query.
     * To be overridden by subclasses where they support additional extensions.
     * @return The supported extension names
     */
    public Set<String> getSupportedExtensions()
    {
        Set<String> supported = super.getSupportedExtensions();
        supported.add(RDBMSPropertyNames.PROPERTY_RDBMS_QUERY_RESULT_SET_TYPE);
        supported.add(RDBMSPropertyNames.PROPERTY_RDBMS_QUERY_RESULT_SET_CONCURRENCY);
        supported.add(RDBMSPropertyNames.PROPERTY_RDBMS_QUERY_FETCH_DIRECTION);
        return supported;
    }

    /**
     * Add a vendor-specific extension this query.
     * Intercepts any setting of in-memory evaluation, so we can throw away any datastore compilation.
     * @param key the extension key
     * @param value the extension value
     */
    public void addExtension(String key, Object value)
    {
        if (key != null && key.equals(EXTENSION_EVALUATE_IN_MEMORY))
        {
            datastoreCompilation = null;
            getQueryManager().removeDatastoreQueryCompilation(getStoreManager().getQueryCacheKey(), getLanguage(), toString());
        }
        super.addExtension(key, value);
    }

    /**
     * Set multiple extensions, or use null to clear extensions.
     * Intercepts any setting of in-memory evaluation, so we can throw away any datastore compilation.
     * @param extensions Query extensions
     */
    public void setExtensions(Map extensions)
    {
        if (extensions != null && extensions.containsKey(EXTENSION_EVALUATE_IN_MEMORY))
        {
            datastoreCompilation = null;
            getQueryManager().removeDatastoreQueryCompilation(getStoreManager().getQueryCacheKey(), getLanguage(), toString());
        }
        super.setExtensions(extensions);
    }

    public RDBMSQueryCompilation getDatastoreCompilation()
    {
        return datastoreCompilation;
    }

    /* (non-Javadoc)
     * @see org.datanucleus.store.query.Query#getNativeQuery()
     */
    @Override
    public Object getNativeQuery()
    {
        if (datastoreCompilation != null)
        {
            return datastoreCompilation.getSQL();
        }
        return super.getNativeQuery();
    }
}