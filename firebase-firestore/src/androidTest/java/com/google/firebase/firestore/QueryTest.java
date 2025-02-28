// Copyright 2018 Google LLC
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//      http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.google.firebase.firestore;

import static com.google.firebase.firestore.testutil.IntegrationTestUtil.nullList;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.querySnapshotToIds;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.querySnapshotToValues;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.testCollection;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.testCollectionWithDocs;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.testFirestore;
import static com.google.firebase.firestore.testutil.IntegrationTestUtil.waitFor;
import static com.google.firebase.firestore.testutil.TestUtil.expectError;
import static com.google.firebase.firestore.testutil.TestUtil.map;
import static java.util.Arrays.asList;
import static java.util.Collections.singletonList;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import com.google.android.gms.tasks.Task;
import com.google.common.collect.Lists;
import com.google.firebase.firestore.Query.Direction;
import com.google.firebase.firestore.testutil.EventAccumulator;
import com.google.firebase.firestore.testutil.IntegrationTestUtil;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class QueryTest {

  @After
  public void tearDown() {
    IntegrationTestUtil.tearDown();
  }

  /**
   * Checks that running the query while online (against the backend/emulator) results in the same
   * documents as running the query while offline. If `expectedDocs` is provided, it also checks
   * that both online and offline query result is equal to the expected documents.
   *
   * @param query The query to check
   * @param expectedDocs Ordered list of document keys that are expected to match the query
   */
  public void checkOnlineAndOfflineResultsMatch(Query query, String... expectedDocs) {
    QuerySnapshot docsFromServer = waitFor(query.get(Source.SERVER));
    QuerySnapshot docsFromCache = waitFor(query.get(Source.CACHE));

    assertEquals(querySnapshotToIds(docsFromServer), querySnapshotToIds(docsFromCache));
    List<String> expected = asList(expectedDocs);
    if (!expected.isEmpty()) {
      assertEquals(expected, querySnapshotToIds(docsFromCache));
    }
  }

  @Test
  public void testLimitQueries() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("k", "a"),
                "b", map("k", "b"),
                "c", map("k", "c")));

    Query query = collection.limit(2);
    QuerySnapshot set = waitFor(query.get());
    List<Map<String, Object>> data = querySnapshotToValues(set);
    assertEquals(asList(map("k", "a"), map("k", "b")), data);
  }

  @Test
  public void testLimitQueriesUsingDescendingSortOrder() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("k", "a", "sort", 0),
                "b", map("k", "b", "sort", 1),
                "c", map("k", "c", "sort", 1),
                "d", map("k", "d", "sort", 2)));

    Query query = collection.limit(2).orderBy("sort", Direction.DESCENDING);
    QuerySnapshot set = waitFor(query.get());
    List<Map<String, Object>> data = querySnapshotToValues(set);
    assertEquals(asList(map("k", "d", "sort", 2L), map("k", "c", "sort", 1L)), data);
  }

  @Test
  public void testLimitToLastMustAlsoHaveExplicitOrderBy() {
    CollectionReference collection = testCollectionWithDocs(map());

    Query query = collection.limitToLast(2);
    expectError(
        () -> waitFor(query.get()),
        "limitToLast() queries require specifying at least one orderBy() clause");
  }

  // Two queries that mapped to the same target ID are referred to as
  // "mirror queries". An example for a mirror query is a limitToLast()
  // query and a limit() query that share the same backend Target ID.
  // Since limitToLast() queries are sent to the backend with a modified
  // orderBy() clause, they can map to the same target representation as
  // limit() query, even if both queries appear separate to the user.
  @Test
  public void testListenUnlistenRelistenSequenceOfMirrorQueries() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("k", "a", "sort", 0),
                "b", map("k", "b", "sort", 1),
                "c", map("k", "c", "sort", 1),
                "d", map("k", "d", "sort", 2)));

    // Setup `limit` query.
    Query limit = collection.limit(2).orderBy("sort", Direction.ASCENDING);
    EventAccumulator<QuerySnapshot> limitAccumulator = new EventAccumulator<>();
    ListenerRegistration limitRegistration = limit.addSnapshotListener(limitAccumulator.listener());

    // Setup mirroring `limitToLast` query.
    Query limitToLast = collection.limitToLast(2).orderBy("sort", Direction.DESCENDING);
    EventAccumulator<QuerySnapshot> limitToLastAccumulator = new EventAccumulator<>();
    ListenerRegistration limitToLastRegistration =
        limitToLast.addSnapshotListener(limitToLastAccumulator.listener());

    // Verify both query get expected result.
    List<Map<String, Object>> data = querySnapshotToValues(limitAccumulator.await());
    assertEquals(asList(map("k", "a", "sort", 0L), map("k", "b", "sort", 1L)), data);
    data = querySnapshotToValues(limitToLastAccumulator.await());
    assertEquals(asList(map("k", "b", "sort", 1L), map("k", "a", "sort", 0L)), data);

    // Unlisten then re-listen limit query.
    limitRegistration.remove();
    limit.addSnapshotListener(limitAccumulator.listener());

    // Verify `limit` query still works.
    data = querySnapshotToValues(limitAccumulator.await());
    assertEquals(asList(map("k", "a", "sort", 0L), map("k", "b", "sort", 1L)), data);

    // Add a document that would change the result set.
    waitFor(collection.add(map("k", "e", "sort", -1)));

    // Verify both query get expected result.
    data = querySnapshotToValues(limitAccumulator.await());
    assertEquals(asList(map("k", "e", "sort", -1L), map("k", "a", "sort", 0L)), data);
    data = querySnapshotToValues(limitToLastAccumulator.await());
    assertEquals(asList(map("k", "a", "sort", 0L), map("k", "e", "sort", -1L)), data);

    // Unlisten to limitToLast, update a doc, then relisten to limitToLast
    limitToLastRegistration.remove();
    waitFor(collection.document("a").update(map("k", "a", "sort", -2)));
    limitToLast.addSnapshotListener(limitToLastAccumulator.listener());

    // Verify both query get expected result.
    data = querySnapshotToValues(limitAccumulator.await());
    assertEquals(asList(map("k", "a", "sort", -2L), map("k", "e", "sort", -1L)), data);
    data = querySnapshotToValues(limitToLastAccumulator.await());
    assertEquals(asList(map("k", "e", "sort", -1L), map("k", "a", "sort", -2L)), data);
  }

  @Test
  public void testLimitToLastQueriesWithCursors() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("k", "a", "sort", 0),
                "b", map("k", "b", "sort", 1),
                "c", map("k", "c", "sort", 1),
                "d", map("k", "d", "sort", 2)));

    Query query = collection.limitToLast(3).orderBy("sort").endBefore(2);
    QuerySnapshot set = waitFor(query.get());
    List<Map<String, Object>> data = querySnapshotToValues(set);
    assertEquals(
        asList(map("k", "a", "sort", 0L), map("k", "b", "sort", 1L), map("k", "c", "sort", 1L)),
        data);

    query = collection.limitToLast(3).orderBy("sort").endAt(1);
    set = waitFor(query.get());
    data = querySnapshotToValues(set);
    assertEquals(
        asList(map("k", "a", "sort", 0L), map("k", "b", "sort", 1L), map("k", "c", "sort", 1L)),
        data);

    query = collection.limitToLast(3).orderBy("sort").startAt(2);
    set = waitFor(query.get());
    data = querySnapshotToValues(set);
    assertEquals(asList(map("k", "d", "sort", 2L)), data);

    query = collection.limitToLast(3).orderBy("sort").startAfter(0);
    set = waitFor(query.get());
    data = querySnapshotToValues(set);
    assertEquals(
        asList(map("k", "b", "sort", 1L), map("k", "c", "sort", 1L), map("k", "d", "sort", 2L)),
        data);

    query = collection.limitToLast(3).orderBy("sort").startAfter(-1);
    set = waitFor(query.get());
    data = querySnapshotToValues(set);
    assertEquals(
        asList(map("k", "b", "sort", 1L), map("k", "c", "sort", 1L), map("k", "d", "sort", 2L)),
        data);
  }

  @Test
  public void testKeyOrderIsDescendingForDescendingInequality() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("foo", 42),
                "b", map("foo", 42.0),
                "c", map("foo", 42),
                "d", map("foo", 21),
                "e", map("foo", 21.0),
                "f", map("foo", 66),
                "g", map("foo", 66.0)));

    Query query = collection.whereGreaterThan("foo", 21.0).orderBy("foo", Direction.DESCENDING);
    QuerySnapshot result = waitFor(query.get());
    assertEquals(asList("g", "f", "c", "b", "a"), querySnapshotToIds(result));
  }

  @Test
  public void testUnaryFilterQueries() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("null", null, "nan", Double.NaN),
                "b", map("null", null, "nan", 0),
                "c", map("null", false, "nan", Double.NaN)));
    QuerySnapshot results =
        waitFor(collection.whereEqualTo("null", null).whereEqualTo("nan", Double.NaN).get());
    assertEquals(1, results.size());
    DocumentSnapshot result = results.getDocuments().get(0);
    // Can't use assertEquals() since NaN != NaN.
    assertEquals(null, result.get("null"));
    assertTrue(((Double) result.get("nan")).isNaN());
  }

  @Test
  public void testFilterOnInfinity() {
    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", map("inf", Double.POSITIVE_INFINITY),
                "b", map("inf", Double.NEGATIVE_INFINITY)));
    QuerySnapshot results = waitFor(collection.whereEqualTo("inf", Double.POSITIVE_INFINITY).get());
    assertEquals(1, results.size());
    assertEquals(asList(map("inf", Double.POSITIVE_INFINITY)), querySnapshotToValues(results));
  }

  @Test
  public void testWillNotGetMetadataOnlyUpdates() {
    CollectionReference collection = testCollection();
    waitFor(collection.document("a").set(map("v", "a")));
    waitFor(collection.document("b").set(map("v", "b")));

    List<QuerySnapshot> snapshots = new ArrayList<>();

    Semaphore testCounter = new Semaphore(0);
    ListenerRegistration listener =
        collection.addSnapshotListener(
            (snapshot, error) -> {
              assertNull(error);
              snapshots.add(snapshot);
              testCounter.release();
            });

    waitFor(testCounter);
    assertEquals(1, snapshots.size());
    assertEquals(asList(map("v", "a"), map("v", "b")), querySnapshotToValues(snapshots.get(0)));
    waitFor(collection.document("a").set(map("v", "a1")));

    waitFor(testCounter);
    assertEquals(2, snapshots.size());
    assertEquals(asList(map("v", "a1"), map("v", "b")), querySnapshotToValues(snapshots.get(1)));

    listener.remove();
  }

  @Test
  public void testCanListenForTheSameQueryWithDifferentOptions() {
    CollectionReference collection = testCollection();
    waitFor(collection.document("a").set(map("v", "a")));
    waitFor(collection.document("b").set(map("v", "b")));

    List<QuerySnapshot> snapshots = new ArrayList<>();
    List<QuerySnapshot> snapshotsFull = new ArrayList<>();

    Semaphore testCounter = new Semaphore(0);
    Semaphore testCounterFull = new Semaphore(0);
    ListenerRegistration listener =
        collection.addSnapshotListener(
            (snapshot, error) -> {
              assertNull(error);
              snapshots.add(snapshot);
              testCounter.release();
            });

    ListenerRegistration listenerFull =
        collection.addSnapshotListener(
            MetadataChanges.INCLUDE,
            (snapshot, error) -> {
              assertNull(error);
              snapshotsFull.add(snapshot);
              testCounterFull.release();
            });

    waitFor(testCounter);
    waitFor(testCounterFull, 2);
    assertEquals(1, snapshots.size());
    assertEquals(asList(map("v", "a"), map("v", "b")), querySnapshotToValues(snapshots.get(0)));
    assertEquals(2, snapshotsFull.size());
    assertEquals(asList(map("v", "a"), map("v", "b")), querySnapshotToValues(snapshotsFull.get(0)));
    assertEquals(asList(map("v", "a"), map("v", "b")), querySnapshotToValues(snapshotsFull.get(1)));
    assertTrue(snapshotsFull.get(0).getMetadata().isFromCache());
    assertFalse(snapshotsFull.get(1).getMetadata().isFromCache());

    waitFor(collection.document("a").set(map("v", "a1")));

    // Expect two events for the write, once from latency compensation and once from the
    // acknowledgement from the server.
    waitFor(testCounterFull, 2);
    // Only one event without options
    waitFor(testCounter);

    assertEquals(4, snapshotsFull.size());
    assertEquals(
        asList(map("v", "a1"), map("v", "b")), querySnapshotToValues(snapshotsFull.get(2)));
    assertEquals(
        asList(map("v", "a1"), map("v", "b")), querySnapshotToValues(snapshotsFull.get(3)));
    assertTrue(snapshotsFull.get(2).getMetadata().hasPendingWrites());
    assertFalse(snapshotsFull.get(3).getMetadata().hasPendingWrites());

    assertEquals(2, snapshots.size());
    assertEquals(asList(map("v", "a1"), map("v", "b")), querySnapshotToValues(snapshots.get(1)));

    waitFor(collection.document("b").set(map("v", "b1")));

    // Expect two events for the write, once from latency compensation and once from the
    // acknowledgement from the server.
    waitFor(testCounterFull, 2);
    // Only one event without options
    waitFor(testCounter);

    assertEquals(6, snapshotsFull.size());
    assertEquals(
        asList(map("v", "a1"), map("v", "b1")), querySnapshotToValues(snapshotsFull.get(4)));
    assertEquals(
        asList(map("v", "a1"), map("v", "b1")), querySnapshotToValues(snapshotsFull.get(5)));
    assertTrue(snapshotsFull.get(4).getMetadata().hasPendingWrites());
    assertFalse(snapshotsFull.get(5).getMetadata().hasPendingWrites());

    assertEquals(3, snapshots.size());
    assertEquals(asList(map("v", "a1"), map("v", "b1")), querySnapshotToValues(snapshots.get(2)));

    listener.remove();
    listenerFull.remove();
  }

  @Test
  public void testCanListenForQueryMetadataChanges() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "1", map("sort", 1.0, "filter", true, "key", "1"),
            "2", map("sort", 2.0, "filter", true, "key", "2"),
            "3", map("sort", 2.0, "filter", true, "key", "3"),
            "4", map("sort", 3.0, "filter", false, "key", "4"));
    CollectionReference collection = testCollectionWithDocs(testDocs);
    List<QuerySnapshot> snapshots = new ArrayList<>();

    Semaphore testCounter = new Semaphore(0);
    Query query1 = collection.whereLessThan("key", "4");
    ListenerRegistration listener1 =
        query1.addSnapshotListener(
            (snapshot, error) -> {
              assertNull(error);
              snapshots.add(snapshot);
              testCounter.release();
            });

    waitFor(testCounter);
    assertEquals(1, snapshots.size());
    assertEquals(
        asList(testDocs.get("1"), testDocs.get("2"), testDocs.get("3")),
        querySnapshotToValues(snapshots.get(0)));

    Query query2 = collection.whereEqualTo("filter", true);
    ListenerRegistration listener2 =
        query2.addSnapshotListener(
            MetadataChanges.INCLUDE,
            (snapshot, error) -> {
              assertNull(error);
              snapshots.add(snapshot);
              testCounter.release();
            });

    waitFor(testCounter, 2);
    assertEquals(3, snapshots.size());
    assertEquals(
        asList(testDocs.get("1"), testDocs.get("2"), testDocs.get("3")),
        querySnapshotToValues(snapshots.get(1)));
    assertEquals(
        asList(testDocs.get("1"), testDocs.get("2"), testDocs.get("3")),
        querySnapshotToValues(snapshots.get(2)));
    assertTrue(snapshots.get(1).getMetadata().isFromCache());
    assertFalse(snapshots.get(2).getMetadata().isFromCache());

    listener1.remove();
    listener2.remove();
  }

  @Test
  public void testCanExplicitlySortByDocumentId() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "a", map("key", "a"),
            "b", map("key", "b"),
            "c", map("key", "c"));
    CollectionReference collection = testCollectionWithDocs(testDocs);
    // Ideally this would be descending to validate it's different than
    // the default, but that requires an extra index
    QuerySnapshot docs = waitFor(collection.orderBy(FieldPath.documentId()).get());
    assertEquals(
        asList(testDocs.get("a"), testDocs.get("b"), testDocs.get("c")),
        querySnapshotToValues(docs));
  }

  @Test
  public void testCanQueryByDocumentId() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "aa", map("key", "aa"),
            "ab", map("key", "ab"),
            "ba", map("key", "ba"),
            "bb", map("key", "bb"));
    CollectionReference collection = testCollectionWithDocs(testDocs);
    QuerySnapshot docs = waitFor(collection.whereEqualTo(FieldPath.documentId(), "ab").get());
    assertEquals(singletonList(testDocs.get("ab")), querySnapshotToValues(docs));

    docs =
        waitFor(
            collection
                .whereGreaterThan(FieldPath.documentId(), "aa")
                .whereLessThanOrEqualTo(FieldPath.documentId(), "ba")
                .get());
    assertEquals(asList(testDocs.get("ab"), testDocs.get("ba")), querySnapshotToValues(docs));
  }

  @Test
  public void testCanQueryByDocumentIdUsingRefs() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "aa", map("key", "aa"),
            "ab", map("key", "ab"),
            "ba", map("key", "ba"),
            "bb", map("key", "bb"));
    CollectionReference collection = testCollectionWithDocs(testDocs);
    QuerySnapshot docs =
        waitFor(collection.whereEqualTo(FieldPath.documentId(), collection.document("ab")).get());
    assertEquals(singletonList(testDocs.get("ab")), querySnapshotToValues(docs));

    docs =
        waitFor(
            collection
                .whereGreaterThan(FieldPath.documentId(), collection.document("aa"))
                .whereLessThanOrEqualTo(FieldPath.documentId(), collection.document("ba"))
                .get());
    assertEquals(asList(testDocs.get("ab"), testDocs.get("ba")), querySnapshotToValues(docs));
  }

  @Test
  public void testCanQueryWithAndWithoutDocumentKey() {
    CollectionReference collection = testCollection();
    collection.add(map());
    Task<QuerySnapshot> query1 =
        collection.orderBy(FieldPath.documentId(), Direction.ASCENDING).get();
    Task<QuerySnapshot> query2 = collection.get();

    waitFor(query1);
    waitFor(query2);

    assertEquals(
        querySnapshotToValues(query1.getResult()), querySnapshotToValues(query2.getResult()));
  }

  @Test
  public void watchSurvivesNetworkDisconnect() {
    CollectionReference collectionReference = testCollection();
    FirebaseFirestore firestore = collectionReference.getFirestore();

    Semaphore receivedDocument = new Semaphore(0);

    collectionReference.addSnapshotListener(
        MetadataChanges.INCLUDE,
        (snapshot, error) -> {
          if (!snapshot.isEmpty() && !snapshot.getMetadata().isFromCache()) {
            receivedDocument.release();
          }
        });

    waitFor(firestore.disableNetwork());
    collectionReference.add(map("foo", FieldValue.serverTimestamp()));
    waitFor(firestore.enableNetwork());

    waitFor(receivedDocument);
  }

  @Test
  public void testQueriesFireFromCacheWhenOffline() {
    Map<String, Map<String, Object>> testDocs = map("a", map("foo", 1L));
    CollectionReference collection = testCollectionWithDocs(testDocs);
    EventAccumulator<QuerySnapshot> accum = new EventAccumulator<>();
    ListenerRegistration listener =
        collection.addSnapshotListener(MetadataChanges.INCLUDE, accum.listener());

    // initial event
    QuerySnapshot querySnapshot = accum.await();
    assertEquals(singletonList(testDocs.get("a")), querySnapshotToValues(querySnapshot));
    assertFalse(querySnapshot.getMetadata().isFromCache());

    // offline event with fromCache=true
    waitFor(collection.firestore.getClient().disableNetwork());
    querySnapshot = accum.await();
    assertTrue(querySnapshot.getMetadata().isFromCache());

    // back online event with fromCache=false
    waitFor(collection.firestore.getClient().enableNetwork());
    querySnapshot = accum.await();
    assertFalse(querySnapshot.getMetadata().isFromCache());

    listener.remove();
  }

  @Test
  public void testQueriesCanRaiseInitialSnapshotFromCachedEmptyResults() {
    CollectionReference collectionReference = testCollection();

    // Populate the cache with empty query result.
    QuerySnapshot querySnapshotA = waitFor(collectionReference.get());
    assertFalse(querySnapshotA.getMetadata().isFromCache());
    assertEquals(asList(), querySnapshotToValues(querySnapshotA));

    // Add a snapshot listener whose first event should be raised from cache.
    EventAccumulator<QuerySnapshot> accum = new EventAccumulator<>();
    ListenerRegistration listenerRegistration =
        collectionReference.addSnapshotListener(accum.listener());
    QuerySnapshot querySnapshotB = accum.await();
    assertTrue(querySnapshotB.getMetadata().isFromCache());
    assertEquals(asList(), querySnapshotToValues(querySnapshotB));

    listenerRegistration.remove();
  }

  @Test
  public void testQueriesCanRaiseInitialSnapshotFromEmptyDueToDeleteCachedResults() {
    Map<String, Map<String, Object>> testDocs = map("a", map("foo", 1L));
    CollectionReference collectionReference = testCollectionWithDocs(testDocs);
    // Populate the cache with single document.
    QuerySnapshot querySnapshotA = waitFor(collectionReference.get());
    assertFalse(querySnapshotA.getMetadata().isFromCache());
    assertEquals(asList(testDocs.get("a")), querySnapshotToValues(querySnapshotA));

    // delete the document, make cached result empty.
    DocumentReference docRef = collectionReference.document("a");
    waitFor(docRef.delete());

    // Add a snapshot listener whose first event should be raised from cache.
    EventAccumulator<QuerySnapshot> accum = new EventAccumulator<>();
    ListenerRegistration listenerRegistration =
        collectionReference.addSnapshotListener(accum.listener());
    QuerySnapshot querySnapshotB = accum.await();
    assertTrue(querySnapshotB.getMetadata().isFromCache());
    assertEquals(asList(), querySnapshotToValues(querySnapshotB));

    listenerRegistration.remove();
  }

  @Test
  public void testQueriesCanUseNotEqualFilters() {
    // These documents are ordered by value in "zip" since the notEquals filter is an inequality,
    // which results in documents being sorted by value.
    Map<String, Object> docA = map("zip", Double.NaN);
    Map<String, Object> docB = map("zip", 91102L);
    Map<String, Object> docC = map("zip", 98101L);
    Map<String, Object> docD = map("zip", "98101");
    Map<String, Object> docE = map("zip", asList(98101L));
    Map<String, Object> docF = map("zip", asList(98101L, 98102L));
    Map<String, Object> docG = map("zip", asList("98101", map("zip", 98101L)));
    Map<String, Object> docH = map("zip", map("code", 500L));
    Map<String, Object> docI = map("code", 500L);
    Map<String, Object> docJ = map("zip", null);

    Map<String, Map<String, Object>> allDocs =
        map(
            "a", docA, "b", docB, "c", docC, "d", docD, "e", docE, "f", docF, "g", docG, "h", docH,
            "i", docI, "j", docJ);
    CollectionReference collection = testCollectionWithDocs(allDocs);

    // Search for zips not matching 98101.
    Map<String, Map<String, Object>> expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("c");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");

    QuerySnapshot snapshot = waitFor(collection.whereNotEqualTo("zip", 98101L).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With objects.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("h");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotEqualTo("zip", map("code", 500)).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With Null.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotEqualTo("zip", null).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With NaN.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("a");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotEqualTo("zip", Double.NaN).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));
  }

  @Test
  public void testQueriesCanUseNotEqualFiltersWithDocIds() {
    Map<String, String> docA = map("key", "aa");
    Map<String, String> docB = map("key", "ab");
    Map<String, String> docC = map("key", "ba");
    Map<String, String> docD = map("key", "bb");
    Map<String, Map<String, Object>> testDocs =
        map(
            "aa", docA,
            "ab", docB,
            "ba", docC,
            "bb", docD);
    CollectionReference collection = testCollectionWithDocs(testDocs);
    QuerySnapshot docs = waitFor(collection.whereNotEqualTo(FieldPath.documentId(), "aa").get());
    assertEquals(asList(docB, docC, docD), querySnapshotToValues(docs));
  }

  @Test
  public void testQueriesCanUseArrayContainsFilters() {
    Map<String, Object> docA = map("array", asList(42L));
    Map<String, Object> docB = map("array", asList("a", 42L, "c"));
    Map<String, Object> docC = map("array", asList(41.999, "42", map("a", asList(42))));
    Map<String, Object> docD = map("array", asList(42L), "array2", asList("bingo"));
    Map<String, Object> docE = map("array", nullList());
    Map<String, Object> docF = map("array", asList(Double.NaN));
    CollectionReference collection =
        testCollectionWithDocs(
            map("a", docA, "b", docB, "c", docC, "d", docD, "e", docE, "f", docF));

    // Search for "array" to contain 42
    QuerySnapshot snapshot = waitFor(collection.whereArrayContains("array", 42L).get());
    assertEquals(asList(docA, docB, docD), querySnapshotToValues(snapshot));

    // Note: whereArrayContains() requires a non-null value parameter, so no null test is needed.
    // With NaN.
    snapshot = waitFor(collection.whereArrayContains("array", Double.NaN).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));
  }

  @Test
  public void testQueriesCanUseInFilters() {
    Map<String, Object> docA = map("zip", 98101L);
    Map<String, Object> docB = map("zip", 91102L);
    Map<String, Object> docC = map("zip", 98103L);
    Map<String, Object> docD = map("zip", asList(98101L));
    Map<String, Object> docE = map("zip", asList("98101", map("zip", 98101L)));
    Map<String, Object> docF = map("zip", map("code", 500L));
    Map<String, Object> docG = map("zip", asList(98101L, 98102L));
    Map<String, Object> docH = map("zip", null);
    Map<String, Object> docI = map("zip", Double.NaN);

    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", docA, "b", docB, "c", docC, "d", docD, "e", docE, "f", docF, "g", docG, "h",
                docH, "i", docI));

    // Search for zips matching 98101, 98103, or [98101, 98102].
    QuerySnapshot snapshot =
        waitFor(collection.whereIn("zip", asList(98101L, 98103L, asList(98101L, 98102L))).get());
    assertEquals(asList(docA, docC, docG), querySnapshotToValues(snapshot));

    // With objects.
    snapshot = waitFor(collection.whereIn("zip", asList(map("code", 500L))).get());
    assertEquals(asList(docF), querySnapshotToValues(snapshot));

    // With null.
    snapshot = waitFor(collection.whereIn("zip", nullList()).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));

    // With null and a value.
    List<Object> inputList = nullList();
    inputList.add(98101L);
    snapshot = waitFor(collection.whereIn("zip", inputList).get());
    assertEquals(asList(docA), querySnapshotToValues(snapshot));

    // With NaN.
    snapshot = waitFor(collection.whereIn("zip", asList(Double.NaN)).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));

    // With NaN and a value.
    snapshot = waitFor(collection.whereIn("zip", asList(Double.NaN, 98101L)).get());
    assertEquals(asList(docA), querySnapshotToValues(snapshot));
  }

  @Test
  public void testQueriesCanUseInFiltersWithDocIds() {
    Map<String, String> docA = map("key", "aa");
    Map<String, String> docB = map("key", "ab");
    Map<String, String> docC = map("key", "ba");
    Map<String, String> docD = map("key", "bb");
    Map<String, Map<String, Object>> testDocs =
        map(
            "aa", docA,
            "ab", docB,
            "ba", docC,
            "bb", docD);
    CollectionReference collection = testCollectionWithDocs(testDocs);
    QuerySnapshot docs =
        waitFor(collection.whereIn(FieldPath.documentId(), asList("aa", "ab")).get());
    assertEquals(asList(docA, docB), querySnapshotToValues(docs));
  }

  @Test
  public void testQueriesCanUseNotInFilters() {
    // These documents are ordered by value in "zip" since the notEquals filter is an inequality,
    // which results in documents being sorted by value.
    Map<String, Object> docA = map("zip", Double.NaN);
    Map<String, Object> docB = map("zip", 91102L);
    Map<String, Object> docC = map("zip", 98101L);
    Map<String, Object> docD = map("zip", 98103L);
    Map<String, Object> docE = map("zip", asList(98101L));
    Map<String, Object> docF = map("zip", asList(98101L, 98102L));
    Map<String, Object> docG = map("zip", asList("98101", map("zip", 98101L)));
    Map<String, Object> docH = map("zip", map("code", 500L));
    Map<String, Object> docI = map("code", 500L);
    Map<String, Object> docJ = map("zip", null);

    Map<String, Map<String, Object>> allDocs =
        map(
            "a", docA, "b", docB, "c", docC, "d", docD, "e", docE, "f", docF, "g", docG, "h", docH,
            "i", docI, "j", docJ);
    CollectionReference collection = testCollectionWithDocs(allDocs);

    // Search for zips not matching 98101, 98103, or [98101, 98102].
    Map<String, Map<String, Object>> expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("c");
    expectedDocsMap.remove("d");
    expectedDocsMap.remove("f");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");

    QuerySnapshot snapshot =
        waitFor(collection.whereNotIn("zip", asList(98101L, 98103L, asList(98101L, 98102L))).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With objects.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("h");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotIn("zip", asList(map("code", 500L))).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With Null.
    snapshot = waitFor(collection.whereNotIn("zip", nullList()).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));

    // With NaN.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("a");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotIn("zip", asList(Double.NaN)).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));

    // With NaN and a number.
    expectedDocsMap = new LinkedHashMap<>(allDocs);
    expectedDocsMap.remove("a");
    expectedDocsMap.remove("c");
    expectedDocsMap.remove("i");
    expectedDocsMap.remove("j");
    snapshot = waitFor(collection.whereNotIn("zip", asList(Float.NaN, 98101L)).get());
    assertEquals(Lists.newArrayList(expectedDocsMap.values()), querySnapshotToValues(snapshot));
  }

  @Test
  public void testQueriesCanUseNotInFiltersWithDocIds() {
    Map<String, String> docA = map("key", "aa");
    Map<String, String> docB = map("key", "ab");
    Map<String, String> docC = map("key", "ba");
    Map<String, String> docD = map("key", "bb");
    Map<String, Map<String, Object>> testDocs =
        map(
            "aa", docA,
            "ab", docB,
            "ba", docC,
            "bb", docD);
    CollectionReference collection = testCollectionWithDocs(testDocs);
    QuerySnapshot docs =
        waitFor(collection.whereNotIn(FieldPath.documentId(), asList("aa", "ab")).get());
    assertEquals(asList(docC, docD), querySnapshotToValues(docs));
  }

  @Test
  public void testQueriesCanUseArrayContainsAnyFilters() {
    Map<String, Object> docA = map("array", asList(42L));
    Map<String, Object> docB = map("array", asList("a", 42L, "c"));
    Map<String, Object> docC = map("array", asList(41.999, "42", map("a", asList(42))));
    Map<String, Object> docD = map("array", asList(42L), "array2", asList("bingo"));
    Map<String, Object> docE = map("array", asList(43L));
    Map<String, Object> docF = map("array", asList(map("a", 42L)));
    Map<String, Object> docG = map("array", 42L);
    Map<String, Object> docH = map("array", nullList());
    Map<String, Object> docI = map("array", asList(Double.NaN));

    CollectionReference collection =
        testCollectionWithDocs(
            map(
                "a", docA, "b", docB, "c", docC, "d", docD, "e", docE, "f", docF, "g", docG, "h",
                docH, "i", docI));

    // Search for "array" to contain [42, 43].
    QuerySnapshot snapshot =
        waitFor(collection.whereArrayContainsAny("array", asList(42L, 43L)).get());
    assertEquals(asList(docA, docB, docD, docE), querySnapshotToValues(snapshot));

    // With objects.
    snapshot = waitFor(collection.whereArrayContainsAny("array", asList(map("a", 42L))).get());
    assertEquals(asList(docF), querySnapshotToValues(snapshot));

    // With null.
    snapshot = waitFor(collection.whereArrayContainsAny("array", nullList()).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));

    // With null and a value.
    List<Object> inputList = nullList();
    inputList.add(43L);
    snapshot = waitFor(collection.whereArrayContainsAny("array", inputList).get());
    assertEquals(asList(docE), querySnapshotToValues(snapshot));

    // With NaN.
    snapshot = waitFor(collection.whereArrayContainsAny("array", asList(Double.NaN)).get());
    assertEquals(new ArrayList<>(), querySnapshotToValues(snapshot));

    // With NaN and a value.
    snapshot = waitFor(collection.whereArrayContainsAny("array", asList(Double.NaN, 43L)).get());
    assertEquals(asList(docE), querySnapshotToValues(snapshot));
  }

  @Test
  public void testCollectionGroupQueries() {
    FirebaseFirestore db = testFirestore();
    // Use .document() to get a random collection group name to use but ensure it starts with 'b'
    // for predictable ordering.
    String collectionGroup = "b" + db.collection("foo").document().getId();

    String[] docPaths =
        new String[] {
          "abc/123/${collectionGroup}/cg-doc1",
          "abc/123/${collectionGroup}/cg-doc2",
          "${collectionGroup}/cg-doc3",
          "${collectionGroup}/cg-doc4",
          "def/456/${collectionGroup}/cg-doc5",
          "${collectionGroup}/virtual-doc/nested-coll/not-cg-doc",
          "x${collectionGroup}/not-cg-doc",
          "${collectionGroup}x/not-cg-doc",
          "abc/123/${collectionGroup}x/not-cg-doc",
          "abc/123/x${collectionGroup}/not-cg-doc",
          "abc/${collectionGroup}"
        };
    WriteBatch batch = db.batch();
    for (String path : docPaths) {
      batch.set(db.document(path.replace("${collectionGroup}", collectionGroup)), map("x", 1));
    }
    waitFor(batch.commit());

    QuerySnapshot querySnapshot = waitFor(db.collectionGroup(collectionGroup).get());
    assertEquals(
        asList("cg-doc1", "cg-doc2", "cg-doc3", "cg-doc4", "cg-doc5"),
        querySnapshotToIds(querySnapshot));
  }

  @Test
  public void testCollectionGroupQueriesWithStartAtEndAtWithArbitraryDocumentIds() {
    FirebaseFirestore db = testFirestore();
    // Use .document() to get a random collection group name to use but ensure it starts with 'b'
    // for predictable ordering.
    String collectionGroup = "b" + db.collection("foo").document().getId();

    String[] docPaths =
        new String[] {
          "a/a/${collectionGroup}/cg-doc1",
          "a/b/a/b/${collectionGroup}/cg-doc2",
          "a/b/${collectionGroup}/cg-doc3",
          "a/b/c/d/${collectionGroup}/cg-doc4",
          "a/c/${collectionGroup}/cg-doc5",
          "${collectionGroup}/cg-doc6",
          "a/b/nope/nope"
        };
    WriteBatch batch = db.batch();
    for (String path : docPaths) {
      batch.set(db.document(path.replace("${collectionGroup}", collectionGroup)), map("x", 1));
    }
    waitFor(batch.commit());

    QuerySnapshot querySnapshot =
        waitFor(
            db.collectionGroup(collectionGroup)
                .orderBy(FieldPath.documentId())
                .startAt("a/b")
                .endAt("a/b0")
                .get());
    assertEquals(asList("cg-doc2", "cg-doc3", "cg-doc4"), querySnapshotToIds(querySnapshot));

    querySnapshot =
        waitFor(
            db.collectionGroup(collectionGroup)
                .orderBy(FieldPath.documentId())
                .startAfter("a/b")
                .endBefore("a/b/" + collectionGroup + "/cg-doc3")
                .get());
    assertEquals(asList("cg-doc2"), querySnapshotToIds(querySnapshot));
  }

  @Test
  public void testCollectionGroupQueriesWithWhereFiltersOnArbitraryDocumentIds() {
    FirebaseFirestore db = testFirestore();
    // Use .document() to get a random collection group name to use but ensure it starts with 'b'
    // for predictable ordering.
    String collectionGroup = "b" + db.collection("foo").document().getId();

    String[] docPaths =
        new String[] {
          "a/a/${collectionGroup}/cg-doc1",
          "a/b/a/b/${collectionGroup}/cg-doc2",
          "a/b/${collectionGroup}/cg-doc3",
          "a/b/c/d/${collectionGroup}/cg-doc4",
          "a/c/${collectionGroup}/cg-doc5",
          "${collectionGroup}/cg-doc6",
          "a/b/nope/nope"
        };
    WriteBatch batch = db.batch();
    for (String path : docPaths) {
      batch.set(db.document(path.replace("${collectionGroup}", collectionGroup)), map("x", 1));
    }
    waitFor(batch.commit());

    QuerySnapshot querySnapshot =
        waitFor(
            db.collectionGroup(collectionGroup)
                .whereGreaterThanOrEqualTo(FieldPath.documentId(), "a/b")
                .whereLessThanOrEqualTo(FieldPath.documentId(), "a/b0")
                .get());
    assertEquals(asList("cg-doc2", "cg-doc3", "cg-doc4"), querySnapshotToIds(querySnapshot));

    querySnapshot =
        waitFor(
            db.collectionGroup(collectionGroup)
                .whereGreaterThan(FieldPath.documentId(), "a/b")
                .whereLessThan(FieldPath.documentId(), "a/b/" + collectionGroup + "/cg-doc3")
                .get());
    assertEquals(asList("cg-doc2"), querySnapshotToIds(querySnapshot));
  }

  // See: https://github.com/firebase/firebase-android-sdk/issues/3528
  // TODO(Overlay): These two tests should be part of local store tests instead.
  @Test
  public void testAddThenUpdatesWhileOffline() {
    CollectionReference collection = testCollection();
    collection.getFirestore().disableNetwork();

    collection.add(map("foo", "zzyzx", "bar", "1"));

    QuerySnapshot snapshot1 = waitFor(collection.get(Source.CACHE));
    assertEquals(asList(map("foo", "zzyzx", "bar", "1")), querySnapshotToValues(snapshot1));
    DocumentReference doc = snapshot1.getDocuments().get(0).getReference();

    doc.update(map("bar", "2"));

    QuerySnapshot snapshot2 = waitFor(collection.get(Source.CACHE));
    assertEquals(asList(map("foo", "zzyzx", "bar", "2")), querySnapshotToValues(snapshot2));
  }

  @Test
  public void testMultipleUpdatesWhileOffline() {
    CollectionReference collection = testCollection();
    collection.getFirestore().disableNetwork();

    DocumentReference doc = collection.document();
    doc.set(map("foo", "zzyzx", "bar", "1"), SetOptions.mergeFields("foo", "bar"));

    QuerySnapshot snapshot1 = waitFor(collection.get(Source.CACHE));
    assertEquals(asList(map("foo", "zzyzx", "bar", "1")), querySnapshotToValues(snapshot1));

    doc.update(map("bar", "2"));

    QuerySnapshot snapshot2 = waitFor(collection.get(Source.CACHE));
    assertEquals(asList(map("foo", "zzyzx", "bar", "2")), querySnapshotToValues(snapshot2));
  }

  // TODO(orquery): Enable this test when prod supports OR queries.
  @Ignore
  @Test
  public void testOrQueries() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", 0),
            "doc2", map("a", 2, "b", 1),
            "doc3", map("a", 3, "b", 2),
            "doc4", map("a", 1, "b", 3),
            "doc5", map("a", 1, "b", 1));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    // Two equalities: a==1 || b==1.
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 1), Filter.equalTo("b", 1))),
        "doc1",
        "doc2",
        "doc4",
        "doc5");

    // with one inequality: a>2 || b==1.
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.greaterThan("a", 2), Filter.equalTo("b", 1))),
        "doc5",
        "doc2",
        "doc3");

    // (a==1 && b==0) || (a==3 && b==2)
    checkOnlineAndOfflineResultsMatch(
        collection.where(
            Filter.or(
                Filter.and(Filter.equalTo("a", 1), Filter.equalTo("b", 0)),
                Filter.and(Filter.equalTo("a", 3), Filter.equalTo("b", 2)))),
        "doc1",
        "doc3");

    // a==1 && (b==0 || b==3).
    checkOnlineAndOfflineResultsMatch(
        collection.where(
            Filter.and(
                Filter.equalTo("a", 1), Filter.or(Filter.equalTo("b", 0), Filter.equalTo("b", 3)))),
        "doc1",
        "doc4");

    // (a==2 || b==2) && (a==3 || b==3)
    checkOnlineAndOfflineResultsMatch(
        collection.where(
            Filter.and(
                Filter.or(Filter.equalTo("a", 2), Filter.equalTo("b", 2)),
                Filter.or(Filter.equalTo("a", 3), Filter.equalTo("b", 3)))),
        "doc3");

    // Test with limits (implicit order by ASC): (a==1) || (b > 0) LIMIT 2
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 1), Filter.greaterThan("b", 0))).limit(2),
        "doc1",
        "doc2");

    // Test with limits (explicit order by): (a==1) || (b > 0) LIMIT_TO_LAST 2
    // Note: The public query API does not allow implicit ordering when limitToLast is used.
    checkOnlineAndOfflineResultsMatch(
        collection
            .where(Filter.or(Filter.equalTo("a", 1), Filter.greaterThan("b", 0)))
            .limitToLast(2)
            .orderBy("b"),
        "doc3",
        "doc4");

    // Test with limits (explicit order by ASC): (a==2) || (b == 1) ORDER BY a LIMIT 1
    checkOnlineAndOfflineResultsMatch(
        collection
            .where(Filter.or(Filter.equalTo("a", 2), Filter.equalTo("b", 1)))
            .limit(1)
            .orderBy("a"),
        "doc5");

    // Test with limits (explicit order by DESC): (a==2) || (b == 1) ORDER BY a LIMIT_TO_LAST 1
    checkOnlineAndOfflineResultsMatch(
        collection
            .where(Filter.or(Filter.equalTo("a", 2), Filter.equalTo("b", 1)))
            .limitToLast(1)
            .orderBy("a"),
        "doc2");

    // Test with limits without orderBy (the __name__ ordering is the tie breaker).
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 2), Filter.equalTo("b", 1))).limit(1),
        "doc2");
  }

  // TODO(orquery): Enable this test when prod supports OR queries.
  @Ignore
  @Test
  public void testOrQueriesWithInAndNotIn() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", 0),
            "doc2", map("b", 1),
            "doc3", map("a", 3, "b", 2),
            "doc4", map("a", 1, "b", 3),
            "doc5", map("a", 1),
            "doc6", map("a", 2));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    // a==2 || b in [2,3]
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 2), Filter.inArray("b", asList(2, 3)))),
        "doc3",
        "doc4",
        "doc6");

    // a==2 || b not-in [2,3]
    // Has implicit orderBy b.
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 2), Filter.notInArray("b", asList(2, 3)))),
        "doc1",
        "doc2");
  }

  // TODO(orquery): Enable this test when prod supports OR queries.
  @Ignore
  @Test
  public void testOrQueriesWithArrayMembership() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", asList(0)),
            "doc2", map("b", asList(1)),
            "doc3", map("a", 3, "b", asList(2, 7)),
            "doc4", map("a", 1, "b", asList(3, 7)),
            "doc5", map("a", 1),
            "doc6", map("a", 2));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    // a==2 || b array-contains 7
    checkOnlineAndOfflineResultsMatch(
        collection.where(Filter.or(Filter.equalTo("a", 2), Filter.arrayContains("b", 7))),
        "doc3",
        "doc4",
        "doc6");

    // a==2 || b array-contains-any [0, 3]
    checkOnlineAndOfflineResultsMatch(
        collection.where(
            Filter.or(Filter.equalTo("a", 2), Filter.arrayContainsAny("b", asList(0, 3)))),
        "doc1",
        "doc4",
        "doc6");
  }

  @Ignore
  @Test
  public void testMultipleInOps() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", 0),
            "doc2", map("b", 1),
            "doc3", map("a", 3, "b", 2),
            "doc4", map("a", 1, "b", 3),
            "doc5", map("a", 1),
            "doc6", map("a", 2));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    // Two IN operations on different fields with disjunction.
    Query query1 =
        collection
            .where(Filter.or(Filter.inArray("a", asList(2, 3)), Filter.inArray("b", asList(0, 2))))
            .orderBy("a");
    checkOnlineAndOfflineResultsMatch(query1, "doc1", "doc6", "doc3");

    // Two IN operations on different fields with conjunction.
    Query query2 =
        collection
            .where(Filter.and(Filter.inArray("a", asList(2, 3)), Filter.inArray("b", asList(0, 2))))
            .orderBy("a");
    checkOnlineAndOfflineResultsMatch(query2, "doc3");

    // Two IN operations on the same field.
    // a IN [1,2,3] && a IN [0,1,4] should result in "a==1".
    Query query3 =
        collection.where(
            Filter.and(Filter.inArray("a", asList(1, 2, 3)), Filter.inArray("a", asList(0, 1, 4))));
    checkOnlineAndOfflineResultsMatch(query3, "doc1", "doc4", "doc5");

    // a IN [2,3] && a IN [0,1,4] is never true and so the result should be an empty set.
    Query query4 =
        collection.where(
            Filter.and(Filter.inArray("a", asList(2, 3)), Filter.inArray("a", asList(0, 1, 4))));
    checkOnlineAndOfflineResultsMatch(query4);

    // a IN [0,3] || a IN [0,2] should union them (similar to: a IN [0,2,3]).
    Query query5 =
        collection.where(
            Filter.or(Filter.inArray("a", asList(0, 3)), Filter.inArray("a", asList(0, 2))));
    checkOnlineAndOfflineResultsMatch(query5, "doc3", "doc6");

    // Nested composite filter on the same field.
    Query query6 =
        collection.where(
            Filter.and(
                Filter.inArray("a", asList(1, 3)),
                Filter.or(
                    Filter.inArray("a", asList(0, 2)),
                    Filter.and(
                        Filter.greaterThanOrEqualTo("b", 1), Filter.inArray("a", asList(1, 3))))));
    checkOnlineAndOfflineResultsMatch(query6, "doc3", "doc4");

    // Nested composite filter on different fields.
    Query query7 =
        collection.where(
            Filter.and(
                Filter.inArray("b", asList(0, 3)),
                Filter.or(
                    Filter.inArray("b", asList(1)),
                    Filter.and(
                        Filter.inArray("b", asList(2, 3)), Filter.inArray("a", asList(1, 3))))));
    checkOnlineAndOfflineResultsMatch(query7, "doc4");
  }

  @Ignore
  @Test
  public void testUsingInWithArrayContainsAny() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", asList(0)),
            "doc2", map("b", asList(1)),
            "doc3", map("a", 3, "b", asList(2, 7), "c", 10),
            "doc4", map("a", 1, "b", asList(3, 7)),
            "doc5", map("a", 1),
            "doc6", map("a", 2, "c", 20));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    Query query1 =
        collection.where(
            Filter.or(
                Filter.inArray("a", asList(2, 3)), Filter.arrayContainsAny("b", asList(0, 7))));
    checkOnlineAndOfflineResultsMatch(query1, "doc1", "doc3", "doc4", "doc6");

    Query query2 =
        collection.where(
            Filter.and(
                Filter.inArray("a", asList(2, 3)), Filter.arrayContainsAny("b", asList(0, 7))));
    checkOnlineAndOfflineResultsMatch(query2, "doc3");

    Query query3 =
        collection.where(
            Filter.or(
                Filter.and(Filter.inArray("a", asList(2, 3)), Filter.equalTo("c", 10)),
                Filter.arrayContainsAny("b", asList(0, 7))));
    checkOnlineAndOfflineResultsMatch(query3, "doc1", "doc3", "doc4");

    Query query4 =
        collection.where(
            Filter.and(
                Filter.inArray("a", asList(2, 3)),
                Filter.or(Filter.arrayContainsAny("b", asList(0, 7)), Filter.equalTo("c", 20))));
    checkOnlineAndOfflineResultsMatch(query4, "doc3", "doc6");
  }

  @Ignore
  @Test
  public void testUsingInWithArrayContains() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", asList(0)),
            "doc2", map("b", asList(1)),
            "doc3", map("a", 3, "b", asList(2, 7)),
            "doc4", map("a", 1, "b", asList(3, 7)),
            "doc5", map("a", 1),
            "doc6", map("a", 2));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    Query query1 =
        collection.where(
            Filter.or(Filter.inArray("a", asList(2, 3)), Filter.arrayContains("b", 3)));
    checkOnlineAndOfflineResultsMatch(query1, "doc3", "doc4", "doc6");

    Query query2 =
        collection.where(
            Filter.and(Filter.inArray("a", asList(2, 3)), Filter.arrayContains("b", 7)));
    checkOnlineAndOfflineResultsMatch(query2, "doc3");

    Query query3 =
        collection.where(
            Filter.or(
                Filter.inArray("a", asList(2, 3)),
                Filter.and(Filter.arrayContains("b", 3), Filter.equalTo("a", 1))));
    checkOnlineAndOfflineResultsMatch(query3, "doc3", "doc4", "doc6");

    Query query4 =
        collection.where(
            Filter.and(
                Filter.inArray("a", asList(2, 3)),
                Filter.or(Filter.arrayContains("b", 7), Filter.equalTo("a", 1))));
    checkOnlineAndOfflineResultsMatch(query4, "doc3");
  }

  @Ignore
  @Test
  public void testOrderByEquality() {
    Map<String, Map<String, Object>> testDocs =
        map(
            "doc1", map("a", 1, "b", asList(0)),
            "doc2", map("b", asList(1)),
            "doc3", map("a", 3, "b", asList(2, 7), "c", 10),
            "doc4", map("a", 1, "b", asList(3, 7)),
            "doc5", map("a", 1),
            "doc6", map("a", 2, "c", 20));
    CollectionReference collection = testCollectionWithDocs(testDocs);

    Query query1 = collection.where(Filter.equalTo("a", 1)).orderBy("a");
    checkOnlineAndOfflineResultsMatch(query1, "doc1", "doc4", "doc5");

    Query query2 = collection.where(Filter.inArray("a", asList(2, 3))).orderBy("a");
    checkOnlineAndOfflineResultsMatch(query2, "doc6", "doc3");
  }
}
