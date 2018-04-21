package kbasesearchengine.events.handler;

import java.io.IOException;
import java.net.ConnectException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.common.base.Optional;
import kbasesearchengine.events.AccessGroupEventQueue;
import kbasesearchengine.events.ObjectEventQueue;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;
import org.joda.time.format.DateTimeFormatterBuilder;

import com.fasterxml.jackson.core.type.TypeReference;

import kbasesearchengine.common.GUID;
import kbasesearchengine.events.ChildStatusEvent;
import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventType;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.ErrorType;
import kbasesearchengine.events.exceptions.FatalIndexingException;
import kbasesearchengine.events.exceptions.FatalRetriableIndexingException;
import kbasesearchengine.events.exceptions.IndexingException;
import kbasesearchengine.events.exceptions.IndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.RetriableIndexingException;
import kbasesearchengine.events.exceptions.RetriableIndexingExceptionUncheckedWrapper;
import kbasesearchengine.events.exceptions.UnprocessableEventIndexingException;
import kbasesearchengine.system.StorageObjectType;
import kbasesearchengine.tools.Utils;
import us.kbase.common.service.JsonClientException;
import us.kbase.common.service.Tuple11;
import us.kbase.common.service.Tuple9;
import us.kbase.common.service.UObject;
import us.kbase.common.service.UnauthorizedException;
import us.kbase.workspace.GetObjectInfo3Params;
import us.kbase.workspace.GetObjectInfo3Results;
import us.kbase.workspace.GetObjects2Params;
import us.kbase.workspace.GetObjects2Results;
import us.kbase.workspace.ListObjectsParams;
import us.kbase.workspace.ObjectData;
import us.kbase.workspace.ObjectIdentity;
import us.kbase.workspace.ObjectSpecification;
import us.kbase.workspace.ProvenanceAction;
import us.kbase.workspace.SubAction;
import us.kbase.workspace.WorkspaceClient;
import us.kbase.workspace.WorkspaceIdentity;


/** A handler for events generated by the workspace service.
 * @author gaprice@lbl.gov
 *
 */
public class WorkspaceEventHandler implements EventHandler {
    
    private final static DateTimeFormatter DATE_PARSER =
            new DateTimeFormatterBuilder()
                .append(DateTimeFormat.forPattern("yyyy-MM-dd'T'HH:mm:ss"))
                .appendOptional(DateTimeFormat.forPattern(".SSS").getParser())
                .append(DateTimeFormat.forPattern("Z"))
                .toFormatter(); 
    
    //TODO TEST

    /** The storage code for workspace events. */
    public static final String STORAGE_CODE = "WS";
    
    private static final int WS_BATCH_SIZE = 10_000;
    
    private static final String META_SEARCH_TAGS = "searchtags";
    
    private static final TypeReference<List<Tuple11<Long, String, String, String,
            Long, String, Long, String, String, Long, Map<String, String>>>> OBJ_TYPEREF =
                    new TypeReference<List<Tuple11<Long, String, String, String,
                            Long, String, Long, String, String, Long, Map<String, String>>>>() {};

    private static final TypeReference<Tuple9<Long, String, String, String, Long, String,
            String, String, Map<String, String>>> WS_INFO_TYPEREF =
                    new TypeReference<Tuple9<Long, String, String, String, Long, String, String,
                            String,Map<String,String>>>() {};
    
    private final CloneableWorkspaceClient ws;
    
    /** Create a handler.
     * @param clonableWorkspaceClient a workspace client to use when contacting the workspace
     * service.
     */
    public WorkspaceEventHandler(final CloneableWorkspaceClient clonableWorkspaceClient) {
        Utils.nonNull(clonableWorkspaceClient, "clonableWorkspaceClient");
        ws = clonableWorkspaceClient;
    }
    
    @Override
    public String getStorageCode() {
        return STORAGE_CODE;
    }
    
    @Override
    public SourceData load(final GUID guid, final Path file)
            throws IndexingException, RetriableIndexingException {
        Utils.nonNull(guid, "guid");
        return load(Arrays.asList(guid), file);
    }

    @Override
    public SourceData load(final List<GUID> guids, final Path file)
            throws IndexingException, RetriableIndexingException {
        Utils.nonNull(guids, "guids");
        Utils.noNulls(guids, "null item in guids");
        Utils.nonNull(file, "file");
        final ObjectData ret = getObjectData(guids, file);
        final List<String> tags = getTags(ret);
        // we'll assume here that there's only one provenance action. This may need more thought
        // if that's not true.
        final ProvenanceAction pa = ret.getProvenance().isEmpty() ?
                null : ret.getProvenance().get(0);
        String copier = ret.getInfo().getE6();
        if (ret.getCopied() == null && ret.getCopySourceInaccessible() == 0) {
            copier = null;
        }
        final SourceData.Builder b = SourceData.getBuilder(
                ret.getData(), ret.getInfo().getE2(), ret.getCreator())
                .withNullableCopier(copier)
                .withNullableMD5(ret.getInfo().getE9());
                //TODO CODE get the timestamp from ret rather than using event timestamp
        for (final String tag: tags) {
            b.withSourceTag(tag);
        }
        if (pa != null) {
            b.withNullableModule(pa.getService())
                    .withNullableMethod(pa.getMethod())
                    .withNullableVersion(pa.getServiceVer());
            /* this is taking specific knowledge about how the KBase execution engine
             * works into account, which I'm not sure is a good idea, but for now it'll do 
             */
            if (pa.getService() != null &&
                    pa.getMethod() != null &&
                    pa.getSubactions() != null &&
                    !pa.getSubactions().isEmpty()) {
                final String modmeth = pa.getService() + "." + pa.getMethod();
                for (final SubAction sa: pa.getSubactions()) {
                    if (modmeth.equals(sa.getName())) {
                        b.withNullableCommitHash(sa.getCommit());
                    }
                }
            }
        }
        return b.build();
    }

    private List<String> getTags(final ObjectData objectdata)
            throws RetriableIndexingException, IndexingException {
        final String tags = getWorkspaceInfoInternal(objectdata.getInfo().getE7()).getE9()
                .get(META_SEARCH_TAGS);
        final List<String> ret = new LinkedList<>();
        if (tags != null) {
            for (String tag: tags.split(",")) {
                tag = tag.trim();
                if (!tag.isEmpty()) {
                    ret.add(tag);
                }
            }
        }
        return ret;
    }
    
    /** Parse a date emitted from the workspace to epoch millis.
     * @param workspaceFormattedDate a workspace timestamp.
     * @return epoch millis.
     */
    public static long parseDateToEpochMillis(final String workspaceFormattedDate) {
        return DATE_PARSER.parseDateTime(workspaceFormattedDate).getMillis();
    }
    
    private Tuple9<Long, String, String, String, Long, String,
                String, String, Map<String, String>> getWorkspaceInfoInternal(
            final long workspaceID)
            throws RetriableIndexingException, IndexingException {
        try {
            return getWorkspaceInfo(workspaceID);
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
    }

    /** Get the workspace information for a workspace from the workspace service to which this
     * handler is communicating.
     * @param workspaceID the integer ID of the workspace.
     * @return the workspace info as returned from the workspace.
     * @throws IOException if an IO exception occurs.
     * @throws JsonClientException if an error retrieving the data occurs.
     */
    public Tuple9<Long, String, String, String, Long, String,
                String, String, Map<String, String>> getWorkspaceInfo(
            final long workspaceID)
            throws IOException, JsonClientException {

        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getWorkspaceInfo");
        command.put("params", new WorkspaceIdentity().withId(workspaceID));
        return ws.getClient().administer(new UObject(command))
                .asClassInstance(WS_INFO_TYPEREF);
    }


    /** Get the workspace information for an object from the workspace service
     * to which this handler is communicating.
     * @param workspaceID the integer ID of the workspace.
     * @param objectId the integer ID of the object.
     * @param verId the integer version ID of the object.
     * @return the object info as returned from the workspace.
     * @throws IOException if an IO exception occurs.
     * @throws JsonClientException if an error retrieving the data occurs.
     */
    private GetObjectInfo3Results getObjectInfo(
            final Long workspaceID,
            final Long objectId,
            final Optional<Long> verId)
            throws IOException, JsonClientException {

        final ObjectSpecification os = new ObjectSpecification().
                withWsid(workspaceID).
                withObjid(objectId).
                withVer(verId.orNull());

        final List<ObjectSpecification> getInfoInput = new ArrayList<>();
        getInfoInput.add(os);

        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjectInfo");
        command.put("params", new GetObjectInfo3Params().withObjects(getInfoInput));

        return ws.getClient().administer(new UObject(command))
                .asClassInstance(GetObjectInfo3Results.class);
    }
    
    
    private ObjectData getObjectData(final List<GUID> guids, final Path file)
            throws RetriableIndexingException, IndexingException {
        // create a new client since we're setting a file for the next response
        // fixes race conditions
        // a clone method would be handy
        final WorkspaceClient wc = ws.getClientClone();
        wc.setStreamingModeOn(true);
        wc._setFileForNextRpcResponse(file.toFile());
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjects");
        command.put("params", new GetObjects2Params().withObjects(
                Arrays.asList(new ObjectSpecification().withRef(toWSRefPath(guids)))));
        try {
            return wc.administer(new UObject(command))
                    .asClassInstance(GetObjects2Results.class)
                    .getData().get(0);
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
    }

    private static IndexingException handleException(final JsonClientException e) {
        if (e instanceof UnauthorizedException) {
            return new FatalIndexingException(ErrorType.OTHER, e.getMessage(), e);
        }
        if (e.getMessage() == null) {
            return new UnprocessableEventIndexingException(
                    ErrorType.OTHER, "Null error message from workspace server", e);
        } else if (e.getMessage().toLowerCase().contains("is deleted") ||
                e.getMessage().toLowerCase().contains("has been deleted")) {
            // need SDK error codes, bleah
            return new UnprocessableEventIndexingException(ErrorType.DELETED, e.getMessage(), e);
        } else if (e.getMessage().toLowerCase().contains("login")) {
            return new FatalIndexingException(
                    ErrorType.OTHER, "Workspace credentials are invalid: " + e.getMessage(), e);
        } else if (e.getMessage().toLowerCase().contains("did not start up properly")) {
            return new FatalIndexingException(ErrorType.OTHER,
                    "Fatal error returned from workspace: " + e.getMessage(), e);
        } else {
            // this may need to be expanded, some errors may require retries or total failures
            return new UnprocessableEventIndexingException(
                    ErrorType.OTHER, "Unrecoverable error from workspace on fetching object: " +
                            e.getMessage(), e);
        }
    }

    private static RetriableIndexingException handleException(final IOException e) {
        if (e instanceof ConnectException) {
            return new FatalRetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
        }
        return new RetriableIndexingException(ErrorType.OTHER, e.getMessage(), e);
    }
    
    @Override
    public Map<GUID, String> buildReferencePaths(
            final List<GUID> refpath,
            final Set<GUID> refs) {
        final String refPrefix = buildRefPrefix(refpath);
        return refs.stream().collect(Collectors.toMap(r -> r, r -> refPrefix + r.toRefString()));
    }
    
    @Override
    public Set<ResolvedReference> resolveReferences(
            final List<GUID> refpath,
            final Set<GUID> refs)
            throws RetriableIndexingException, IndexingException {
        // may need to split into batches 
        final String refPrefix = buildRefPrefix(refpath);
        
        final List<GUID> orderedRefs = new ArrayList<>(refs);
        
        final List<ObjectSpecification> getInfoInput = orderedRefs.stream().map(
                ref -> new ObjectSpecification().withRef(refPrefix + ref.toRefString())).collect(
                        Collectors.toList());
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjectInfo");
        command.put("params", new GetObjectInfo3Params().withObjects(getInfoInput));
        
        final GetObjectInfo3Results res;
        try {
            res = ws.getClient().administer(new UObject(command))
                    .asClassInstance(GetObjectInfo3Results.class);
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
        final Set<ResolvedReference> ret = new HashSet<>();
        for (int i = 0; i < orderedRefs.size(); i++) {
            ret.add(createResolvedReference(orderedRefs.get(i), res.getInfos().get(i)));
        }
        return ret;
    }

    private String buildRefPrefix(final List<GUID> refpath) {
        //TODO CODE check storage code
        return refpath == null || refpath.isEmpty() ? "" :
            WorkspaceEventHandler.toWSRefPath(refpath) + ";";
    }
    
    private ResolvedReference createResolvedReference(
            final GUID guid,
            final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                    Long, Map<String, String>> obj) {
        return new ResolvedReference(
                guid,
                new GUID(STORAGE_CODE, Math.toIntExact(obj.getE7()), obj.getE1() + "",
                        Math.toIntExact(obj.getE5()), null, null),
                new StorageObjectType(STORAGE_CODE, obj.getE3().split("-")[0],
                      Integer.parseInt(obj.getE3().split("-")[1].split("\\.")[0])),
                Instant.ofEpochMilli(parseDateToEpochMillis(obj.getE4())));
    }

    @Override
    public boolean isExpandable(final StoredStatusEvent parentEvent) {
        checkStorageCode(parentEvent);
        return EXPANDABLES.contains(parentEvent.getEvent().getEventType());
        
    };
    
    private static final Set<StatusEventType> EXPANDABLES = new HashSet<>(Arrays.asList(
            StatusEventType.NEW_ALL_VERSIONS,
            StatusEventType.COPY_ACCESS_GROUP,
            StatusEventType.DELETE_ACCESS_GROUP,
            StatusEventType.PUBLISH_ACCESS_GROUP,
            StatusEventType.UNPUBLISH_ACCESS_GROUP));
    
    @Override
    public Iterable<ChildStatusEvent> expand(final StoredStatusEvent eventWID)
            throws IndexingException, RetriableIndexingException {
        checkStorageCode(eventWID);
        final StatusEvent event = eventWID.getEvent();
        if (StatusEventType.NEW_ALL_VERSIONS.equals(event.getEventType())) {
            return handleNewAllVersions(eventWID);
        } else if (StatusEventType.COPY_ACCESS_GROUP.equals(event.getEventType())) {
            return handleNewAccessGroup(eventWID);
        } else if (StatusEventType.DELETE_ACCESS_GROUP.equals(event.getEventType())) {
            return handleDeletedAccessGroup(eventWID);
        } else if (StatusEventType.PUBLISH_ACCESS_GROUP.equals(event.getEventType())) {
            return handlePublishAccessGroup(eventWID, StatusEventType.PUBLISH_ALL_VERSIONS);
        } else if (StatusEventType.UNPUBLISH_ACCESS_GROUP.equals(event.getEventType())) {
            return handlePublishAccessGroup(eventWID, StatusEventType.UNPUBLISH_ALL_VERSIONS);
        } else {
            throw new IllegalArgumentException("Unexpandable event type: " + event.getEventType());
        }
    }

    private void checkStorageCode(final StoredStatusEvent event) {
        checkStorageCode(event.getEvent().getStorageCode());
    }

    private void checkStorageCode(final String storageCode) {
        if (!STORAGE_CODE.equals(storageCode)) {
            throw new IllegalArgumentException("This handler only accepts "
                    + STORAGE_CODE + "events");
        }
    }

    private Iterable<ChildStatusEvent> handlePublishAccessGroup(
            final StoredStatusEvent event,
            final StatusEventType newType)
            throws RetriableIndexingException, IndexingException {

        final long workspaceId = (long) event.getEvent().getAccessGroupId().get();
        
        final long objcount;
        try {
            objcount = getWorkspaceInfo(workspaceId).getE5();
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
        return new Iterable<ChildStatusEvent>() {
            
            @Override
            public Iterator<ChildStatusEvent> iterator() {
                return new StupidWorkspaceObjectIterator(event, objcount, newType);
            }
        };
    }

    private Iterable<ChildStatusEvent> handleDeletedAccessGroup(final StoredStatusEvent event) {
        
        return new Iterable<ChildStatusEvent>() {

            @Override
            public Iterator<ChildStatusEvent> iterator() {
                return new StupidWorkspaceObjectIterator(
                        event, Long.parseLong(event.getEvent().getAccessGroupObjectId().get()),
                        StatusEventType.DELETE_ALL_VERSIONS);
            }
            
        };
    }
    
    /* This is not efficient, but allows parallelizing events by decomposing the event
     * to per object events. That means the parallelization can run on a per object basis.
     */
    private static class StupidWorkspaceObjectIterator implements Iterator<ChildStatusEvent> {

        private final StoredStatusEvent event;
        private final StatusEventType newType;
        private final long maxObjectID;
        private long counter = 0;
        
        public StupidWorkspaceObjectIterator(
                final StoredStatusEvent event,
                final long maxObjectID,
                final StatusEventType newType) {
            this.event = event;
            this.maxObjectID = maxObjectID;
            this.newType = newType;
        }

        @Override
        public boolean hasNext() {
            return counter < maxObjectID;
        }

        @Override
        public ChildStatusEvent next() {
            if (counter >= maxObjectID) {
                throw new NoSuchElementException();
            }
            return new ChildStatusEvent(
                    StatusEvent.getBuilder(STORAGE_CODE, event.getEvent().getTimestamp(), newType)
                            .withNullableAccessGroupID(event.getEvent().getAccessGroupId().get())
                            .withNullableObjectID(++counter + "")
                            .build(),
                    event.getID());
        }
        
    }

    private Iterable<ChildStatusEvent> handleNewAccessGroup(final StoredStatusEvent event) {
        return new Iterable<ChildStatusEvent>() {

            @Override
            public Iterator<ChildStatusEvent> iterator() {
                return new WorkspaceIterator(ws.getClient(), event);
            }
            
        };
    }
    
    private static class WorkspaceIterator implements Iterator<ChildStatusEvent> {
        
        private final WorkspaceClient ws;
        private final StoredStatusEvent sourceEvent;
        private final int accessGroupId;
        private long processedObjs = 0;
        private LinkedList<ChildStatusEvent> queue = new LinkedList<>();

        public WorkspaceIterator(final WorkspaceClient ws, final StoredStatusEvent sourceEvent) {
            this.ws = ws;
            this.sourceEvent = sourceEvent;
            this.accessGroupId = sourceEvent.getEvent().getAccessGroupId().get();
            fillQueue();
        }

        @Override
        public boolean hasNext() {
            return !queue.isEmpty();
        }

        @Override
        public ChildStatusEvent next() {
            if (queue.isEmpty()) {
                throw new NoSuchElementException();
            }
            final ChildStatusEvent event = queue.removeFirst();
            if (queue.isEmpty()) {
                fillQueue();
            }
            return event;
        }

        private void fillQueue() {
            // as of 0.7.2 if only object id filters are used, workspace will sort by
            // ws asc, obj id asc, ver dec
            
            final ArrayList<ChildStatusEvent> events;
            final Map<String, Object> command = new HashMap<>();
            command.put("command", "listObjects");
            command.put("params", new ListObjectsParams()
                    .withIds(Arrays.asList((long) accessGroupId))
                    .withMinObjectID((long) processedObjs + 1)
                    .withShowHidden(1L)
                    .withShowAllVersions(1L));
            try {
                events = buildEvents(sourceEvent, ws.administer(new UObject(command))
                        .asClassInstance(OBJ_TYPEREF));
            } catch (IOException e) {
                throw new RetriableIndexingExceptionUncheckedWrapper(handleException(e));
            } catch (JsonClientException e) {
                throw new IndexingExceptionUncheckedWrapper(handleException(e));
            }
            if (events.isEmpty()) {
                return;
            }
            // might want to do something smarter about the extra parse at some point
            final long first = Long.parseLong(events.get(0).getEvent()
                    .getAccessGroupObjectId().get());
            final ChildStatusEvent lastEv = events.get(events.size() - 1);
            long last = Long.parseLong(lastEv.getEvent().getAccessGroupObjectId().get());
            // it cannot be true that there were <10K objects and the last object returned's
            // version was != 1
            if (first == last && events.size() == WS_BATCH_SIZE &&
                    lastEv.getEvent().getVersion().get() != 1) {
                //holy poopsnacks, a > 10K version object
                queue.addAll(events);
                for (int i = lastEv.getEvent().getVersion().get(); i > 1; i =- WS_BATCH_SIZE) {
                    fillQueueWithVersions(first, i - WS_BATCH_SIZE, i);
                }
            } else {
                // could be smarter about this later, rather than throwing away all the versions of
                // the last object
                // not too many objects will have enough versions to matter
                if (lastEv.getEvent().getVersion().get() != 1) {
                    last--;
                }
                for (final ChildStatusEvent e: events) {
                    if (Long.parseLong(e.getEvent().getAccessGroupObjectId().get()) > last) { // *&@ parse
                        break;
                    }
                    queue.add(e);
                }
            }
            processedObjs = last;
        }

        // startVersion = inclusive, endVersion = exclusive
        private void fillQueueWithVersions(
                final long objectID,
                int startVersion,
                final int endVersion) {
            if (startVersion < 1) {
                startVersion = 1;
            }
            final List<ObjectSpecification> objs = new LinkedList<>();
            for (int ver = startVersion; ver < endVersion; ver++) {
                objs.add(new ObjectSpecification()
                        .withWsid((long) accessGroupId)
                        .withObjid(objectID)
                        .withVer((long) ver));
            }
            final Map<String, Object> command = new HashMap<>();
            command.put("command", "getObjectInfo");
            command.put("params", new GetObjectInfo3Params().withObjects(objs));
            try {
                queue.addAll(buildEvents(sourceEvent, ws.administer(new UObject(command))
                        .asClassInstance(GetObjectInfo3Results.class).getInfos()));
            } catch (IOException e) {
                throw new RetriableIndexingExceptionUncheckedWrapper(handleException(e));
            } catch (JsonClientException e) {
                throw new IndexingExceptionUncheckedWrapper(handleException(e));
            }
        }
    }

    private ArrayList<ChildStatusEvent> handleNewAllVersions(final StoredStatusEvent eventWID)
            throws IndexingException, RetriableIndexingException {
        final StatusEvent event = eventWID.getEvent();
        final long objid;
        try {
            objid = Long.parseLong(event.getAccessGroupObjectId().get());
        } catch (NumberFormatException ne) {
            throw new UnprocessableEventIndexingException(ErrorType.OTHER,
                    "Illegal workspace object id: " + event.getAccessGroupObjectId());
        }
        final Map<String, Object> command = new HashMap<>();
        command.put("command", "getObjectHistory");
        command.put("params", new ObjectIdentity()
                .withWsid((long) event.getAccessGroupId().get())
                .withObjid(objid));
        try {
            return buildEvents(eventWID, ws.getClient().administer(new UObject(command))
                    .asClassInstance(OBJ_TYPEREF));
        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
    }

    private static ArrayList<ChildStatusEvent> buildEvents(
            final StoredStatusEvent originalEvent,
            final List<Tuple11<Long, String, String, String, Long, String, Long, String,
                    String, Long, Map<String, String>>> objects) {
        final ArrayList<ChildStatusEvent> events = new ArrayList<>();
        for (final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                Long, Map<String, String>> obj: objects) {
            events.add(buildEvent(originalEvent, obj));
        }
        return events;
    }
    
    private static ChildStatusEvent buildEvent(
            final StoredStatusEvent origEvent,
            final Tuple11<Long, String, String, String, Long, String, Long, String, String,
                    Long, Map<String, String>> obj) {
        final StorageObjectType storageObjectType = new StorageObjectType(
                STORAGE_CODE, obj.getE3().split("-")[0],
                Integer.parseInt(obj.getE3().split("-")[1].split("\\.")[0]));
        return new ChildStatusEvent(StatusEvent.getBuilder(
                storageObjectType,
                Instant.ofEpochMilli(parseDateToEpochMillis(obj.getE4())),
                StatusEventType.NEW_VERSION)
                .withNullableAccessGroupID(origEvent.getEvent().getAccessGroupId().get())
                .withNullableObjectID(obj.getE1() + "")
                .withNullableVersion(Math.toIntExact(obj.getE5()))
                .withNullableisPublic(origEvent.getEvent().isPublic().get())
                .build(),
                origEvent.getID());
    }
    
    private static String toWSRefPath(final List<GUID> objectRefPath) {
        final List<String> refpath = new LinkedList<>();
        for (final GUID g: objectRefPath) {
            if (!g.getStorageCode().equals(STORAGE_CODE)) {
                throw new IllegalArgumentException(String.format(
                        "GUID %s is not a workspace object", g));
            }
            refpath.add(g.getAccessGroupId() + "/" + g.getAccessGroupObjectId() + "/" +
                    g.getVersion());
        }
        return String.join(";", refpath);
    }

    private final StatusEvent updateEventForDeletion(final StatusEvent ev)
              throws RetriableIndexingException,
                     IndexingException {
        // wsid and objid of the object that the specified StatusEvent is an event for
        final int wsid = ev.getAccessGroupId().get();
        final long objid = Long.valueOf(ev.getAccessGroupObjectId().get()).longValue();

        // check if object is permanently deleted or marked as deleted
        final Map<String, Object> command;
        command = new HashMap<>();
        command.put("command", "listObjects");
        command.put("params", new ListObjectsParams().
                withIds(Arrays.asList((long)wsid)).
                withMinObjectID(objid).
                withMaxObjectID(objid));
        try {
            final List<List> objList = ws.getClient().administer(new UObject(command))
                    .asClassInstance(List.class);

            if (objList.isEmpty()) {
                if(ev.getStorageObjectType().isPresent()) {
                    return StatusEvent.getBuilder(ev.getStorageObjectType().get(),
                            ev.getTimestamp(),
                            StatusEventType.DELETE_ALL_VERSIONS).
                            withNullableAccessGroupID(wsid).
                            withNullableObjectID(Long.toString(objid)).build();
                } else {
                    return StatusEvent.
                            getBuilder(ev.getStorageCode(),
                                    ev.getTimestamp(),
                                    StatusEventType.DELETE_ALL_VERSIONS).
                            withNullableAccessGroupID(wsid).
                            withNullableObjectID(Long.toString(objid)).build();
                }
            }
        } catch (IOException ex) {
            throw handleException(ex);
        } catch (JsonClientException ex) {
            if (ex.getMessage().contains("Workspace "+wsid+" is deleted")) {
                if (ev.getStorageObjectType().isPresent()) {
                    return StatusEvent.getBuilder(ev.getStorageObjectType().get(),
                            ev.getTimestamp(),
                            StatusEventType.DELETE_ALL_VERSIONS).
                            withNullableAccessGroupID(wsid).
                            withNullableObjectID(Long.toString(objid)).build();
                } else {
                    return StatusEvent.
                            getBuilder(ev.getStorageCode(),
                                    ev.getTimestamp(),
                                    StatusEventType.DELETE_ALL_VERSIONS).
                            withNullableAccessGroupID(wsid).
                            withNullableObjectID(Long.toString(objid)).build();
                }
            }
            else {
                throw handleException(ex);
            }
        }

        return ev;
    }

    private String getLatestName(StatusEvent ev)
                     throws IndexingException,
                            RetriableIndexingException {

        // wsid and objid of the object that the specified StatusEvent is an event for
        final long wsid = ev.getAccessGroupId().get();
        final long objid = Long.decode(ev.getAccessGroupObjectId().get());

        // check if name has changed and update state of lastestName
        try {
            return getObjectInfo(wsid, objid, Optional.absent()).
                                                              getInfos().get(0).getE2();

        } catch (IOException e) {
            throw handleException(e);
        } catch (JsonClientException e) {
            throw handleException(e);
        }
    }

    private Boolean getLatestIsPublic(StatusEvent ev)
                            throws IndexingException,
                                   RetriableIndexingException {
        try {

            final String isPublic = getWorkspaceInfo(ev.getAccessGroupId().get()).getE6();

            return (isPublic == "n") ? false: true;

        } catch (IOException ex) {
            throw handleException(ex);
        } catch (JsonClientException ex) {
            throw handleException(ex);
        }
    }

    @Override
    public StatusEvent updateObjectEvent(final StatusEvent ev)
            throws IndexingException,
                   RetriableIndexingException {

        // only object level events are updated below
        if (!ObjectEventQueue.isObjectLevelEvent(ev)) {
            return ev;
        }

        // brute force get latest state and update event as event can be played
        // out of order in the case of failed events being replayed.

        // check deletion state
        {
            final StatusEvent updatedEvent = updateEventForDeletion(ev);
            // if event was updated (event type gets changed to
            // StatusEventType.DELETE_ALL_VERSIONS
            if (!updatedEvent.equals(ev)) {
                return updatedEvent;
            }
        }

        // get latest name
        final String latestName = getLatestName(ev);

        // check if permissions have changed and update state of isPublic
        final Boolean latestIsPublic = getLatestIsPublic(ev);

        // storage object type present and required for new version events
        final StatusEvent.Builder bb;
        if(ev.getStorageObjectType().isPresent()) {
            bb = StatusEvent.getBuilder(ev.getStorageObjectType().get(),
                                        ev.getTimestamp(),
                                        ev.getEventType());
        }
        // all other events other than new version events
        else {
            bb = StatusEvent.getBuilder(ev.getStorageCode(),
                                        ev.getTimestamp(),
                                        ev.getEventType());
        }

        return bb.withNullableAccessGroupID(ev.getAccessGroupId().get()).
                withNullableObjectID(ev.getAccessGroupObjectId().get()).
                withNullableVersion(ev.getVersion().orNull()).
                withNullableisPublic(latestIsPublic).
                withNullableNewName(latestName).build();
    }
}
