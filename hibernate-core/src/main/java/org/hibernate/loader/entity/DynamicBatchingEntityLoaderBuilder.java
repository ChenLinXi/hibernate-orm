/*
 * Hibernate, Relational Persistence for Idiomatic Java
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later.
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.loader.entity;

import java.io.Serializable;
import java.lang.reflect.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.hibernate.LockMode;
import org.hibernate.LockOptions;
import org.hibernate.dialect.pagination.LimitHelper;
import org.hibernate.engine.spi.EntityKey;
import org.hibernate.engine.spi.LoadQueryInfluencers;
import org.hibernate.engine.spi.PersistenceContext;
import org.hibernate.engine.spi.QueryParameters;
import org.hibernate.engine.spi.RowSelection;
import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.internal.util.StringHelper;
import org.hibernate.internal.util.collections.ArrayHelper;
import org.hibernate.internal.util.collections.CollectionHelper;
import org.hibernate.loader.spi.AfterLoadAction;
import org.hibernate.persister.entity.MultiLoadOptions;
import org.hibernate.persister.entity.OuterJoinLoadable;
import org.hibernate.pretty.MessageHelper;
import org.hibernate.type.Type;

import org.jboss.logging.Logger;

/**
 * A BatchingEntityLoaderBuilder that builds UniqueEntityLoader instances capable of dynamically building
 * its batch-fetch SQL based on the actual number of entity ids waiting to be fetched.
 *
 * @author Steve Ebersole
 */
public class DynamicBatchingEntityLoaderBuilder extends BatchingEntityLoaderBuilder {
	private static final Logger log = Logger.getLogger( DynamicBatchingEntityLoaderBuilder.class );

	public static final DynamicBatchingEntityLoaderBuilder INSTANCE = new DynamicBatchingEntityLoaderBuilder();

	@SuppressWarnings("unchecked")
	public List multiLoad(
			OuterJoinLoadable persister,
			Serializable[] ids,
			SharedSessionContractImplementor session,
			MultiLoadOptions loadOptions) {
		List result = CollectionHelper.arrayList( ids.length );

		if ( loadOptions.isSessionCheckingEnabled() ) {
			// the user requested that we exclude ids corresponding to already managed
			// entities from the generated load SQL.  So here we will iterate all
			// incoming id values and see whether it corresponds to an existing
			// entity associated with the PC - if it does we add it to the result
			// list immediately and remove its id from the group of ids to load.
			boolean foundAnyManagedEntities = false;
			final List<Serializable> nonManagedIds = new ArrayList<Serializable>();
			for ( Serializable id : ids ) {
				final EntityKey entityKey = new EntityKey( id, persister );
				final Object managedEntity = session.getPersistenceContext().getEntity( entityKey );
				if ( managedEntity != null ) {
					foundAnyManagedEntities = true;
					result.add( managedEntity );
				}
				else {
					nonManagedIds.add( id );
				}
			}

			if ( foundAnyManagedEntities ) {
				if ( nonManagedIds.isEmpty() ) {
					// all of the given ids were already associated with the Session
					return result;
				}
				else {
					// over-write the ids to be loaded with the collection of
					// just non-managed ones
					ids = nonManagedIds.toArray(
							(Serializable[]) Array.newInstance(
									ids.getClass().getComponentType(),
									nonManagedIds.size()
							)
					);
				}
			}
		}

		final LockOptions lockOptions = (loadOptions.getLockOptions() == null)
				? new LockOptions( LockMode.NONE )
				: loadOptions.getLockOptions();

		int numberOfIdsLeft = ids.length;
		final int maxBatchSize;
		if ( loadOptions.getBatchSize() != null && loadOptions.getBatchSize() > 0 ) {
			maxBatchSize = loadOptions.getBatchSize();
		}
		else {
			maxBatchSize = session.getJdbcServices().getJdbcEnvironment().getDialect().getDefaultBatchLoadSizingStrategy().determineOptimalBatchLoadSize(
					persister.getIdentifierType().getColumnSpan( session.getFactory() ),
					numberOfIdsLeft
			);
		}

		int idPosition = 0;
		while ( numberOfIdsLeft > 0 ) {
			int batchSize =  Math.min( numberOfIdsLeft, maxBatchSize );
			final DynamicEntityLoader batchingLoader = new DynamicEntityLoader(
					persister,
					batchSize,
					lockOptions,
					session.getFactory(),
					session.getLoadQueryInfluencers()
			);

			Serializable[] idsInBatch = new Serializable[batchSize];
			System.arraycopy( ids, idPosition, idsInBatch, 0, batchSize );

			QueryParameters qp = buildMultiLoadQueryParameters( persister, idsInBatch, lockOptions );
			result.addAll( batchingLoader.doEntityBatchFetch( session, qp, idsInBatch ) );

			numberOfIdsLeft = numberOfIdsLeft - batchSize;
			idPosition += batchSize;
		}

		return result;
	}

	public static QueryParameters buildMultiLoadQueryParameters(
			OuterJoinLoadable persister,
			Serializable[] ids,
			LockOptions lockOptions) {
		Type[] types = new Type[ids.length];
		Arrays.fill( types, persister.getIdentifierType() );

		QueryParameters qp = new QueryParameters();
		qp.setOptionalEntityName( persister.getEntityName() );
		qp.setPositionalParameterTypes( types );
		qp.setPositionalParameterValues( ids );
		qp.setLockOptions( lockOptions );
		qp.setOptionalObject( null );
		qp.setOptionalId( null );
		return qp;
	}


	@Override
	protected UniqueEntityLoader buildBatchingLoader(
			OuterJoinLoadable persister,
			int batchSize,
			LockMode lockMode,
			SessionFactoryImplementor factory,
			LoadQueryInfluencers influencers) {
		return new DynamicBatchingEntityLoader( persister, batchSize, lockMode, factory, influencers );
	}

	@Override
	protected UniqueEntityLoader buildBatchingLoader(
			OuterJoinLoadable persister,
			int batchSize,
			LockOptions lockOptions,
			SessionFactoryImplementor factory,
			LoadQueryInfluencers influencers) {
		return new DynamicBatchingEntityLoader( persister, batchSize, lockOptions, factory, influencers );
	}

	public static class DynamicBatchingEntityLoader extends BatchingEntityLoader {
		private final int maxBatchSize;
		private final UniqueEntityLoader singleKeyLoader;
		private final DynamicEntityLoader dynamicLoader;

		public DynamicBatchingEntityLoader(
				OuterJoinLoadable persister,
				int maxBatchSize,
				LockMode lockMode,
				SessionFactoryImplementor factory,
				LoadQueryInfluencers loadQueryInfluencers) {
			super( persister );
			this.maxBatchSize = maxBatchSize;
			this.singleKeyLoader = new EntityLoader( persister, 1, lockMode, factory, loadQueryInfluencers );
			this.dynamicLoader = new DynamicEntityLoader( persister, maxBatchSize, lockMode, factory, loadQueryInfluencers );
		}

		public DynamicBatchingEntityLoader(
				OuterJoinLoadable persister,
				int maxBatchSize,
				LockOptions lockOptions,
				SessionFactoryImplementor factory,
				LoadQueryInfluencers loadQueryInfluencers) {
			super( persister );
			this.maxBatchSize = maxBatchSize;
			this.singleKeyLoader = new EntityLoader( persister, 1, lockOptions, factory, loadQueryInfluencers );
			this.dynamicLoader = new DynamicEntityLoader( persister, maxBatchSize, lockOptions, factory, loadQueryInfluencers );
		}

		@Override
		public Object load(
				Serializable id,
				Object optionalObject,
				SharedSessionContractImplementor session,
				LockOptions lockOptions) {
			final Serializable[] batch = session.getPersistenceContext()
					.getBatchFetchQueue()
					.getEntityBatch( persister(), id, maxBatchSize, persister().getEntityMode() );

			final int numberOfIds = ArrayHelper.countNonNull( batch );
			if ( numberOfIds <= 1 ) {
				return singleKeyLoader.load( id, optionalObject, session );
			}

			final Serializable[] idsToLoad = new Serializable[numberOfIds];
			System.arraycopy( batch, 0, idsToLoad, 0, numberOfIds );

			if ( log.isDebugEnabled() ) {
				log.debugf( "Batch loading entity: %s", MessageHelper.infoString( persister(), idsToLoad, session.getFactory() ) );
			}

			QueryParameters qp = buildQueryParameters( id, idsToLoad, optionalObject, lockOptions );
			List results = dynamicLoader.doEntityBatchFetch( session, qp, idsToLoad );
			return getObjectFromList( results, id, session );
		}
	}


	private static class DynamicEntityLoader extends EntityLoader {
		// todo : see the discussion on org.hibernate.loader.collection.DynamicBatchingCollectionInitializerBuilder.DynamicBatchingCollectionLoader

		private final String sqlTemplate;
		private final String alias;

		public DynamicEntityLoader(
				OuterJoinLoadable persister,
				int maxBatchSize,
				LockOptions lockOptions,
				SessionFactoryImplementor factory,
				LoadQueryInfluencers loadQueryInfluencers) {
			this( persister, maxBatchSize, lockOptions.getLockMode(), factory, loadQueryInfluencers );
		}

		public DynamicEntityLoader(
				OuterJoinLoadable persister,
				int maxBatchSize,
				LockMode lockMode,
				SessionFactoryImplementor factory,
				LoadQueryInfluencers loadQueryInfluencers) {
			super( persister, -1, lockMode, factory, loadQueryInfluencers );

			EntityJoinWalker walker = new EntityJoinWalker(
					persister,
					persister.getIdentifierColumnNames(),
					-1,
					lockMode,
					factory,
					loadQueryInfluencers
			) {
				@Override
				protected StringBuilder whereString(String alias, String[] columnNames, int batchSize) {
					return StringHelper.buildBatchFetchRestrictionFragment(
							alias,
							columnNames,
							getFactory().getDialect()
					);
				}
			};

			initFromWalker( walker );
			this.sqlTemplate = walker.getSQLString();
			this.alias = walker.getAlias();
			postInstantiate();

			if ( LOG.isDebugEnabled() ) {
				LOG.debugf(
						"SQL-template for dynamic entity [%s] batch-fetching [%s] : %s",
						entityName,
						lockMode,
						sqlTemplate
				);
			}
		}

		@Override
		protected boolean isSingleRowLoader() {
			return false;
		}

		public List doEntityBatchFetch(
				SharedSessionContractImplementor session,
				QueryParameters queryParameters,
				Serializable[] ids) {
			final String sql = StringHelper.expandBatchIdPlaceholder(
					sqlTemplate,
					ids,
					alias,
					persister.getKeyColumnNames(),
					session.getJdbcServices().getJdbcEnvironment().getDialect()
			);

			try {
				final PersistenceContext persistenceContext = session.getPersistenceContext();
				boolean defaultReadOnlyOrig = persistenceContext.isDefaultReadOnly();
				if ( queryParameters.isReadOnlyInitialized() ) {
					// The read-only/modifiable mode for the query was explicitly set.
					// Temporarily set the default read-only/modifiable setting to the query's setting.
					persistenceContext.setDefaultReadOnly( queryParameters.isReadOnly() );
				}
				else {
					// The read-only/modifiable setting for the query was not initialized.
					// Use the default read-only/modifiable from the persistence context instead.
					queryParameters.setReadOnly( persistenceContext.isDefaultReadOnly() );
				}
				persistenceContext.beforeLoad();
				List results;
				try {
					try {
						results = doTheLoad( sql, queryParameters, session );
					}
					finally {
						persistenceContext.afterLoad();
					}
					persistenceContext.initializeNonLazyCollections();
					log.debug( "Done batch load" );
					return results;
				}
				finally {
					// Restore the original default
					persistenceContext.setDefaultReadOnly( defaultReadOnlyOrig );
				}
			}
			catch ( SQLException sqle ) {
				throw session.getJdbcServices().getSqlExceptionHelper().convert(
						sqle,
						"could not load an entity batch: " + MessageHelper.infoString(
								getEntityPersisters()[0],
								ids,
								session.getFactory()
						),
						sql
				);
			}
		}

		private List doTheLoad(String sql, QueryParameters queryParameters, SharedSessionContractImplementor session) throws SQLException {
			final RowSelection selection = queryParameters.getRowSelection();
			final int maxRows = LimitHelper.hasMaxRows( selection ) ?
					selection.getMaxRows() :
					Integer.MAX_VALUE;

			final List<AfterLoadAction> afterLoadActions = new ArrayList<>();
			final SqlStatementWrapper wrapper = executeQueryStatement( sql, queryParameters, false, afterLoadActions, session );
			final ResultSet rs = wrapper.getResultSet();
			final Statement st = wrapper.getStatement();
			try {
				return processResultSet( rs, queryParameters, session, false, null, maxRows, afterLoadActions );
			}
			finally {
				session.getJdbcCoordinator().getLogicalConnection().getResourceRegistry().release( st );
				session.getJdbcCoordinator().afterStatementExecution();
			}
		}
	}
}
