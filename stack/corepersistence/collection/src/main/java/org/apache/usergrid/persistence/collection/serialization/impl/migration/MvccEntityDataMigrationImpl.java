/*
 *
 *  * Licensed to the Apache Software Foundation (ASF) under one or more
 *  *  contributor license agreements.  The ASF licenses this file to You
 *  * under the Apache License, Version 2.0 (the "License"); you may not
 *  * use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.  For additional information regarding
 *  * copyright in this work, please see the NOTICE file in the top level
 *  * directory of this distribution.
 *
 */
package org.apache.usergrid.persistence.collection.serialization.impl.migration;


import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.usergrid.persistence.collection.CollectionScope;
import org.apache.usergrid.persistence.collection.EntityVersionCleanupFactory;
import org.apache.usergrid.persistence.collection.MvccEntity;
import org.apache.usergrid.persistence.collection.impl.EntityVersionCleanupTask;
import org.apache.usergrid.persistence.collection.serialization.MvccEntitySerializationStrategy;
import org.apache.usergrid.persistence.collection.serialization.UniqueValue;
import org.apache.usergrid.persistence.collection.serialization.UniqueValueSerializationStrategy;
import org.apache.usergrid.persistence.collection.serialization.impl.MvccEntitySerializationStrategyImpl;
import org.apache.usergrid.persistence.collection.serialization.impl.MvccEntitySerializationStrategyV3Impl;
import org.apache.usergrid.persistence.collection.serialization.impl.UniqueValueImpl;
import org.apache.usergrid.persistence.collection.util.EntityUtils;
import org.apache.usergrid.persistence.core.migration.data.DataMigrationException;
import org.apache.usergrid.persistence.core.migration.data.MigrationInfoSerialization;
import org.apache.usergrid.persistence.core.migration.data.newimpls.DataMigration2;
import org.apache.usergrid.persistence.core.migration.data.newimpls.MigrationDataProvider;
import org.apache.usergrid.persistence.core.migration.data.newimpls.MigrationRelationship;
import org.apache.usergrid.persistence.core.migration.data.newimpls.ProgressObserver;
import org.apache.usergrid.persistence.core.migration.data.newimpls.VersionedMigrationSet;
import org.apache.usergrid.persistence.model.entity.Entity;
import org.apache.usergrid.persistence.model.entity.Id;
import org.apache.usergrid.persistence.model.field.Field;
import org.apache.usergrid.persistence.model.util.UUIDGenerator;

import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.netflix.astyanax.Keyspace;
import com.netflix.astyanax.MutationBatch;
import com.netflix.astyanax.connectionpool.exceptions.ConnectionException;

import rx.Observable;
import rx.Subscriber;
import rx.functions.Action0;
import rx.functions.Action1;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.observables.GroupedObservable;
import rx.schedulers.Schedulers;


/**
 * Data migration strategy for entities
 */
@Singleton
public class MvccEntityDataMigrationImpl implements DataMigration2<EntityIdScope> {

    private final Keyspace keyspace;
    private final VersionedMigrationSet<MvccEntitySerializationStrategy> allVersions;
    private final UniqueValueSerializationStrategy uniqueValueSerializationStrategy;
    private final EntityVersionCleanupFactory entityVersionCleanupFactory;
    private final MvccEntitySerializationStrategyV3Impl mvccEntitySerializationStrategyV3;


    @Inject
    public MvccEntityDataMigrationImpl( final Keyspace keyspace,
                                        final VersionedMigrationSet<MvccEntitySerializationStrategy> allVersions,
                                        final UniqueValueSerializationStrategy uniqueValueSerializationStrategy,
                                        final EntityVersionCleanupFactory entityVersionCleanupFactory,
                                        final MvccEntitySerializationStrategyV3Impl mvccEntitySerializationStrategyV3 ) {

        this.keyspace = keyspace;
        this.allVersions = allVersions;
        this.uniqueValueSerializationStrategy = uniqueValueSerializationStrategy;
        this.entityVersionCleanupFactory = entityVersionCleanupFactory;
        this.mvccEntitySerializationStrategyV3 = mvccEntitySerializationStrategyV3;
    }


    @Override
    public boolean supports( final int currentVersion ) {
        //we can only migrate up to v3 with this implementation.  Beyond that, we should use a different migration
        return currentVersion < mvccEntitySerializationStrategyV3.getImplementationVersion();
    }


    @Override
    public int getMaxVersion() {
        return mvccEntitySerializationStrategyV3.getImplementationVersion();
    }


    @Override
    public int migrate( final int currentVersion, final MigrationDataProvider<EntityIdScope> migrationDataProvider,
                         final ProgressObserver observer ) {

        final AtomicLong atomicLong = new AtomicLong();

        //capture the time the test starts

        final UUID startTime = UUIDGenerator.newTimeUUID();

        final MigrationRelationship<MvccEntitySerializationStrategy> migration =
                allVersions.getMigrationRelationship( currentVersion );


        final long migrated = migrationDataProvider.getData().subscribeOn( Schedulers.io() )
                                                   .parallel( new Func1<Observable<EntityIdScope>, Observable<Long>>() {


                  //process the ids in parallel
                  @Override
                  public Observable<Long> call(
                          final Observable<EntityIdScope>
                                  entityIdScopeObservable ) {


                      return entityIdScopeObservable.flatMap(
                              new Func1<EntityIdScope,
                                      Observable<EntityToSaveMessage>>() {


                                  @Override
                                  public
                                  Observable<EntityToSaveMessage> call(
                                          final EntityIdScope
                                                  entityIdScope ) {

                                      //load the entity
                                      final CollectionScope
                                              currentScope =
                                              entityIdScope
                                                      .getCollectionScope();


                                      //for each element in our
                                      // history, we need to copy it
                                      // to v2.
                                      // Note that
                                      // this migration
                                      //won't support anything beyond V2

                                      final Iterator<MvccEntity>
                                              allVersions =
                                              migration.from
                                                      .loadAscendingHistory(
                                                              currentScope,
                                                              entityIdScope
                                                                      .getId(),
                                                              startTime,
                                                              100 );

                                      //emit all the entity versions
                                      return Observable.create(
                                              new Observable
                                                      .OnSubscribe<EntityToSaveMessage>() {
                                                  @Override
                                                  public void call(
                                                          final
                                                          Subscriber<? super
                                                                  EntityToSaveMessage> subscriber ) {

                                                      while ( allVersions
                                                              .hasNext() ) {
                                                          final
                                                          EntityToSaveMessage
                                                                  message =
                                                                  new EntityToSaveMessage(
                                                                          currentScope,
                                                                          allVersions
                                                                                  .next() );
                                                          subscriber.onNext( message );
                                                      }

                                                      subscriber.onCompleted();
                                                  }
                                              } );
                                  }
                              } )


                              //group them by entity id so we can get
                              // the max for cleanup
                              .groupBy(
                                      new Func1<EntityToSaveMessage,
                                              Id>() {
                                          @Override
                                          public Id call(
                                                  final
                                                  EntityToSaveMessage
                                                          entityToSaveMessage ) {
                                              return entityToSaveMessage.entity
                                                      .getId();
                                          }
                                      } )
                              //buffer up 10 of groups so we can put them all in a single mutation
                              .buffer( 10 ).doOnNext(
                                      new Action1<List<GroupedObservable<Id, EntityToSaveMessage>>>() {


                                          @Override
                                          public void call(
                                                  final
                                                  List<GroupedObservable<Id, EntityToSaveMessage>> groupedObservables ) {

                                              atomicLong.addAndGet(
                                                      groupedObservables
                                                              .size() );

                                              final MutationBatch
                                                      totalBatch =
                                                      keyspace.prepareMutationBatch();


                                              //run each of the
                                              // groups and add
                                              // it ot the batch
                                              Observable
                                                      .from( groupedObservables )
                                                      //emit the group as an observable
                                                      .flatMap(
                                                              new Func1<GroupedObservable<Id, EntityToSaveMessage>, Observable<EntityToSaveMessage>>() {


                                                                  @Override
                                                                  public Observable<EntityToSaveMessage> call(
                                                                          final GroupedObservable<Id, EntityToSaveMessage> idEntityToSaveMessageGroupedObservable ) {
                                                                      return idEntityToSaveMessageGroupedObservable
                                                                              .asObservable();
                                                                  }
                                                              } )

                                                      //merge and add the batch
                                                      .doOnNext(
                                                              new Action1<EntityToSaveMessage>() {
                                                                  @Override
                                                                  public void call(
                                                                          final EntityToSaveMessage message ) {

                                                                      final MutationBatch
                                                                              entityRewrite =
                                                                              migration.to.write( message.scope,
                                                                                              message.entity );

                                                                      //add to
                                                                      // the
                                                                      // total
                                                                      // batch
                                                                      totalBatch.mergeShallow( entityRewrite );

                                                                      //write
                                                                      // the
                                                                      // unique values

                                                                      if ( !message.entity
                                                                              .getEntity()
                                                                              .isPresent() ) {
                                                                          return;
                                                                      }

                                                                          final Entity
                                                                                  entity =
                                                                                  message.entity
                                                                                          .getEntity()
                                                                                          .get();

                                                                          final Id
                                                                                  entityId =
                                                                                  entity.getId();

                                                                          final UUID
                                                                                  version =
                                                                                  message.entity
                                                                                          .getVersion();

                                                                          // re-write the unique values
                                                                          // but this
                                                                          // time with
                                                                          // no TTL so that cleanup can clean up older values
                                                                          for ( Field field : EntityUtils
                                                                                  .getUniqueFields(
                                                                                          message.entity
                                                                                                  .getEntity()
                                                                                                  .get() ) ) {

                                                                              UniqueValue
                                                                                      written =
                                                                                      new UniqueValueImpl(
                                                                                              field,
                                                                                              entityId,
                                                                                              version );

                                                                              MutationBatch
                                                                                      mb =
                                                                                      uniqueValueSerializationStrategy
                                                                                              .write( message.scope,
                                                                                                      written );


                                                                              // merge into our
                                                                              // existing mutation
                                                                              // batch
                                                                              totalBatch
                                                                                      .mergeShallow(
                                                                                              mb );
                                                                          }
                                                                  }
                                                              } )
                                                      //once we've streamed everything, flush it
                                                      .doOnCompleted(

                                                              new Action0() {
                                                                  @Override
                                                                  public void call() {

                                                                      executeBatch(migration.to.getImplementationVersion(), totalBatch, observer, atomicLong );
                                                                  }
                                                              } )
                                                      .toBlocking()
                                                      .last();
                                          }
                                      } ).
                                      reduce( 0l,
                                              new Func2<Long, List<GroupedObservable<Id, EntityToSaveMessage>>, Long>() {

                                                  @Override
                                                  public Long call(
                                                          final Long aLong,
                                                          final List<GroupedObservable<Id, EntityToSaveMessage>> groupedObservables ) {

                                                      long newCount =
                                                              aLong;

                                                      for ( GroupedObservable<Id, EntityToSaveMessage> group : groupedObservables ) {
                                                          newCount +=
                                                                  group.longCount()
                                                                       .toBlocking()
                                                                       .last();
                                                      }

                                                      return newCount;
                                                  }
                                              }


                                            );
                  }}).toBlocking().last();

        //now we update the progress observer

        observer.update( migration.to.getImplementationVersion(), "Finished for this step.  Migrated " + migrated + "entities total. " );

        return migration.to.getImplementationVersion();
    }


    protected void executeBatch( final int targetVersion, final MutationBatch batch, final ProgressObserver po, final AtomicLong count ) {
        try {
            batch.execute();

            po.update(targetVersion, "Finished copying " + count + " entities to the new format" );
        }
        catch ( ConnectionException e ) {
            po.failed( targetVersion, "Failed to execute mutation in cassandra" );
            throw new DataMigrationException( "Unable to migrate batches ", e );
        }
    }




    private static final class EntityToSaveMessage {
        private final CollectionScope scope;
        private final MvccEntity entity;


        private EntityToSaveMessage( final CollectionScope scope, final MvccEntity entity ) {
            this.scope = scope;
            this.entity = entity;
        }
    }
}
