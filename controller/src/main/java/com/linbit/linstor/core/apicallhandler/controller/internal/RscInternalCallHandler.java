package com.linbit.linstor.core.apicallhandler.controller.internal;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinstorParsingUtils;
import com.linbit.linstor.annotation.ApiContext;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.interfaces.RscLayerDataApi;
import com.linbit.linstor.api.interfaces.VlmLayerDataApi;
import com.linbit.linstor.api.interfaces.serializer.CtrlStltSerializer;
import com.linbit.linstor.api.pojo.CapacityInfoPojo;
import com.linbit.linstor.core.CoreModule;
import com.linbit.linstor.core.apicallhandler.CtrlRscLayerDataMerger;
import com.linbit.linstor.core.apicallhandler.controller.CtrlApiDataLoader;
import com.linbit.linstor.core.apicallhandler.controller.CtrlTransactionHelper;
import com.linbit.linstor.core.apicallhandler.response.ApiDatabaseException;
import com.linbit.linstor.core.identifier.NodeName;
import com.linbit.linstor.core.identifier.ResourceName;
import com.linbit.linstor.core.identifier.StorPoolName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.AbsResource;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.Snapshot;
import com.linbit.linstor.core.objects.SnapshotDefinition;
import com.linbit.linstor.core.objects.StorPool;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.core.repository.NodeRepository;
import com.linbit.linstor.core.repository.ResourceDefinitionRepository;
import com.linbit.linstor.dbdrivers.DatabaseException;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.netcom.Peer;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.InvalidValueException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.data.provider.utils.ProviderUtils;
import com.linbit.linstor.storage.interfaces.categories.resource.AbsRscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.tasks.RetryResourcesTask;
import com.linbit.linstor.utils.layer.LayerRscUtils;
import com.linbit.locks.LockGuard;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Provider;

import java.time.Instant;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.function.Function;
import java.util.stream.Collectors;

public class RscInternalCallHandler
{
    private final ErrorReporter errorReporter;
    private final AccessContext apiCtx;
    private final CtrlTransactionHelper ctrlTransactionHelper;
    private final CtrlStltSerializer ctrlStltSerializer;
    private final Provider<Peer> peer;
    private final CtrlApiDataLoader apiDataLoader;

    private final NodeRepository nodeRepository;
    private final ResourceDefinitionRepository resourceDefinitionRepository;

    private final ReadWriteLock nodesMapLock;
    private final ReadWriteLock rscDfnMapLock;
    private final ReadWriteLock storPoolDfnMapLock;
    private final CtrlRscLayerDataMerger layerRscDataMerger;
    private final RetryResourcesTask retryResourceTask;
    private final CtrlSatelliteUpdater stltUpdater;
    private final SnapshotShippingInternalApiCallHandler snapShipIntHandler;

    @Inject
    public RscInternalCallHandler(
        ErrorReporter errorReporterRef,
        @ApiContext AccessContext apiCtxRef,
        CtrlTransactionHelper ctrlTransactionHelperRef,
        NodeRepository nodeRepositoryRef,
        ResourceDefinitionRepository resourceDefinitionRepositoryRef,
        CtrlStltSerializer ctrlStltSerializerRef,
        Provider<Peer> peerRef,
        @Named(CoreModule.NODES_MAP_LOCK) ReadWriteLock nodesMapLockRef,
        @Named(CoreModule.RSC_DFN_MAP_LOCK) ReadWriteLock rscDfnMapLockRef,
        @Named(CoreModule.STOR_POOL_DFN_MAP_LOCK) ReadWriteLock storPoolDfnMapLockRef,
        CtrlRscLayerDataMerger layerRscDataMergerRef,
        RetryResourcesTask retryResourceTaskRef,
        CtrlApiDataLoader ctrlApiDataLoader,
        CtrlSatelliteUpdater stltUpdaterRef,
        SnapshotShippingInternalApiCallHandler snapShipIntHandlerRef
    )
    {
        errorReporter = errorReporterRef;
        apiCtx = apiCtxRef;
        ctrlTransactionHelper = ctrlTransactionHelperRef;
        nodeRepository = nodeRepositoryRef;
        resourceDefinitionRepository = resourceDefinitionRepositoryRef;
        ctrlStltSerializer = ctrlStltSerializerRef;
        peer = peerRef;

        nodesMapLock = nodesMapLockRef;
        rscDfnMapLock = rscDfnMapLockRef;
        storPoolDfnMapLock = storPoolDfnMapLockRef;
        layerRscDataMerger = layerRscDataMergerRef;
        retryResourceTask = retryResourceTaskRef;
        apiDataLoader = ctrlApiDataLoader;
        stltUpdater = stltUpdaterRef;
        snapShipIntHandler = snapShipIntHandlerRef;
    }

    public void handleResourceRequest(
        String nodeNameStr,
        UUID rscUuid,
        String rscNameStr
    )
    {
        try (
            LockGuard ls = LockGuard.createLocked(
                nodesMapLock.readLock(),
                rscDfnMapLock.readLock(),
                storPoolDfnMapLock.readLock(),
                peer.get().getSerializerLock().readLock()
            )
        )
        {
            NodeName nodeName = new NodeName(nodeNameStr);

            Node node = nodeRepository.get(apiCtx, nodeName); // TODO use CtrlApiLoader.loadNode

            if (node != null)
            {
                ResourceName rscName = new ResourceName(rscNameStr);
                Resource rsc = !node.isDeleted() ? node.getResource(apiCtx, rscName) : null;

                long fullSyncTimestamp = peer.get().getFullSyncId();
                long updateId = peer.get().getNextSerializerId();
                // TODO: check if the localResource has the same uuid as rscUuid
                if (rsc != null && !rsc.isDeleted())
                {
                    peer.get().sendMessage(
                        ctrlStltSerializer
                            .onewayBuilder(InternalApiConsts.API_APPLY_RSC)
                            .resource(rsc, fullSyncTimestamp, updateId)
                            .build()
                    );
                }
                else
                {
                    peer.get().sendMessage(
                        ctrlStltSerializer
                            .onewayBuilder(InternalApiConsts.API_APPLY_RSC_DELETED)
                            .deletedResource(rscNameStr, fullSyncTimestamp, updateId)
                            .build()
                    );
                }
            }
            else
            {
                errorReporter.reportError(
                    new ImplementationError(
                        "Satellite requested resource '" + rscNameStr + "' on node '" + nodeNameStr + "' " +
                            "but that node does not exist.",
                        null
                    )
                );
                peer.get().closeConnection();
            }
        }
        catch (InvalidNameException invalidNameExc)
        {
            errorReporter.reportError(
                new ImplementationError(
                    "Satellite requested data for invalid name (node or rsc name).",
                    invalidNameExc
                )
            );
        }
        catch (AccessDeniedException accDeniedExc)
        {
            errorReporter.reportError(
                new ImplementationError(
                    "Controller's api context has not enough privileges to gather requested resource data.",
                    accDeniedExc
                )
            );
        }
    }

    public void updateVolume(
        String resourceName,
        RscLayerDataApi rscLayerDataPojoRef,
        Map<String, String> rscPropsRef,
        Map<Integer, Map<String, String>> vlmPropsRef,
        List<CapacityInfoPojo> capacityInfos
    )
    {
        try (LockGuard ls = LockGuard.createLocked(nodesMapLock.readLock(), rscDfnMapLock.writeLock()))
        {
            /*
             * be careful setting this to true - otherwise we could run into infinite loop between
             * ctrl -> rsc changed -> stlt
             * ...
             * ctrl <- rsc applied <- stlt
             * ctrl -> rsc changed -> stlt
             *
             */
            boolean updateSatellite = false;

            NodeName nodeName = peer.get().getNode().getName();
            Map<StorPoolName, CapacityInfoPojo> storPoolToCapacityInfoMap = capacityInfos.stream().collect(
                Collectors.toMap(
                    freeSpacePojo -> LinstorParsingUtils.asStorPoolName(freeSpacePojo.getStorPoolName()),
                    Function.identity()
                )
            );
            ResourceDefinition rscDfn = resourceDefinitionRepository.get(apiCtx, new ResourceName(resourceName));
            Resource rsc = rscDfn.getResource(apiCtx, nodeName);

            if (rsc.getCreateTimestamp().isPresent() &&
                rsc.getCreateTimestamp().get().equals(new Date(AbsResource.CREATE_DATE_INIT_VALUE)))
            {
                rsc.setCreateTimestamp(apiCtx, new Date(Instant.now().toEpochMilli()));
            }

            layerRscDataMerger.mergeLayerData(rsc, rscLayerDataPojoRef, false);
            mergeStltProps(rscPropsRef, rsc.getProps(apiCtx));

            Set<AbsRscLayerObject<Resource>> storageResources = LayerRscUtils.getRscDataByProvider(
                rsc.getLayerData(apiCtx),
                DeviceLayerKind.STORAGE
            );

            Iterator<Volume> iterateVolumes = rsc.iterateVolumes();
            while (iterateVolumes.hasNext())
            {
                Volume vlm = iterateVolumes.next();
                VolumeNumber vlmNr = vlm.getVolumeDefinition().getVolumeNumber();

                VlmLayerDataApi vlmLayerDataPojo = rscLayerDataPojoRef.getVolumeMap().get(vlmNr.value);

                mergeStltProps(vlmPropsRef.get(vlmNr.value), vlm.getProps(apiCtx));

                if (vlmLayerDataPojo != null)
                {
                    vlm.setDevicePath(apiCtx, vlmLayerDataPojo.getDevicePath());
                    vlm.setUsableSize(apiCtx, vlmLayerDataPojo.getUsableSize());
                    vlm.setAllocatedSize(apiCtx, ProviderUtils.getAllocatedSize(vlm, apiCtx));
                    vlm.clearReports();

                    for (AbsRscLayerObject<Resource> storageRsc : storageResources)
                    {
                        VlmProviderObject<Resource> vlmProviderObject = storageRsc.getVlmProviderObject(vlmNr);
                        if (vlmProviderObject != null)
                        {
                            StorPool storPool = vlmProviderObject.getStorPool();

                            CapacityInfoPojo capacityInfo =
                                storPoolToCapacityInfoMap.get(storPool.getName());

                            storPool.getFreeSpaceTracker().vlmCreationFinished(
                                apiCtx,
                                vlmProviderObject,
                                capacityInfo == null ? null : capacityInfo.getFreeCapacity(),
                                    capacityInfo == null ? null : capacityInfo.getTotalCapacity()
                                );

                            if (capacityInfo == null && !storPool.getDeviceProviderKind().usesThinProvisioning())
                            {
                                errorReporter.logWarning(
                                    String.format(
                                        "No freespace info for storage pool '%s' on node: %s",
                                        storPool.getName().value,
                                        nodeName.displayValue
                                    )
                                );
                            }
                        }
                    }
                }
                else
                {
                    errorReporter.logWarning(
                        String.format(
                            "Tried to update a volume with missing layer data. Node: %s, Resource: %s, VolumeNr: %d",
                            nodeName.displayValue,
                            rscDfn.getName().displayValue,
                            vlm.getVolumeDefinition().getVolumeNumber().value
                        )
                    );
                }
            }

            /*
             * TODO: instead of this loop, we should introduce a "notifySnapshotApplied"
             * and put the logic of this loop there
             */
            for (SnapshotDefinition snapDfn : rsc.getDefinition().getSnapshotDfns(apiCtx))
            {
                Snapshot snap = snapDfn.getSnapshot(apiCtx, nodeName);
                if (snap != null && snap.getFlags().isSet(apiCtx, Snapshot.Flags.SHIPPING_TARGET))
                {
                    if (snapShipIntHandler.startShipping(snap))
                    {
                        updateSatellite = true;
                    }
                }
            }

            if (rsc.getStateFlags().isSet(apiCtx, Resource.Flags.BACKUP_RESTORE))
            {
                rsc.getStateFlags().disableFlags(apiCtx, Resource.Flags.BACKUP_RESTORE);
            }

            retryResourceTask.remove(rsc);
            ctrlTransactionHelper.commit();

            // only update satellite after transaction was successfully committed
            if (updateSatellite)
            {
                stltUpdater.updateSatellites(rsc);
            }
        }
        catch (InvalidNameException | AccessDeniedException | DatabaseException exc)
        {
            throw new ImplementationError(exc);
        }
    }


    public void handleResourceFailed(String nodeName, String rscName, ApiCallRc apiCallRc)
    {
        try (LockGuard ls = LockGuard.createLocked(nodesMapLock.readLock(), rscDfnMapLock.readLock()))
        {
            Resource rsc = apiDataLoader.loadRsc(
                nodeName,
                rscName,
                true
            );
            retryResourceTask.add(rsc, null);
        }
    }

    private void mergeStltProps(Map<String, String> srcPropsMap, Props targetProps)
    {
        /*
         * only merge properties from the "Satellite" namespace.
         * other properties might have been added or deleted in the meantime, but the satellite
         * did not get the update yet so those properties would be undone / restored now.
         */
        Optional<Props> stltNs = targetProps.getNamespace(ApiConsts.NAMESPC_STLT);
        if (stltNs.isPresent())
        {
            Props stltProps = stltNs.get();
            stltProps.keySet().retainAll(srcPropsMap.keySet());
        }
        try
        {
            for (Entry<String, String> entry : srcPropsMap.entrySet())
            {
                String key = entry.getKey();
                if (key.startsWith(ApiConsts.NAMESPC_STLT))
                {
                    targetProps.setProp(key, entry.getValue());
                }
            }
        }
        catch (InvalidKeyException | InvalidValueException | AccessDeniedException exc)
        {
            throw new ImplementationError(exc);
        }
        catch (DatabaseException exc)
        {
            throw new ApiDatabaseException(exc);
        }
    }
}
