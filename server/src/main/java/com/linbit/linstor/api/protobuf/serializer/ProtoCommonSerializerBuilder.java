package com.linbit.linstor.api.protobuf.serializer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import com.google.protobuf.ByteString;

import com.linbit.ImplementationError;
import com.linbit.linstor.FreeSpaceTracker;
import com.linbit.linstor.NetInterface;
import com.linbit.linstor.Node;
import com.linbit.linstor.NodeName;
import com.linbit.linstor.Resource;
import com.linbit.linstor.ResourceConnection;
import com.linbit.linstor.ResourceDefinition;
import com.linbit.linstor.ResourceName;
import com.linbit.linstor.StorPool;
import com.linbit.linstor.StorPoolDefinition;
import com.linbit.linstor.StorPoolName;
import com.linbit.linstor.TcpPortNumber;
import com.linbit.linstor.Volume;
import com.linbit.linstor.Volume.VlmApi;
import com.linbit.linstor.VolumeDefinition;
import com.linbit.linstor.VolumeDefinition.VlmDfnApi;
import com.linbit.linstor.api.ApiCallRc;
import com.linbit.linstor.api.interfaces.RscLayerDataPojo;
import com.linbit.linstor.api.interfaces.VlmLayerDataPojo;
import com.linbit.linstor.api.interfaces.serializer.CommonSerializer;
import com.linbit.linstor.api.pojo.CryptSetupRscPojo;
import com.linbit.linstor.api.pojo.CryptSetupRscPojo.CryptVlmPojo;
import com.linbit.linstor.api.pojo.DrbdRscPojo;
import com.linbit.linstor.api.pojo.StorageRscPojo;
import com.linbit.linstor.api.pojo.DrbdRscPojo.DrbdRscDfnPojo;
import com.linbit.linstor.api.pojo.DrbdRscPojo.DrbdVlmDfnPojo;
import com.linbit.linstor.api.pojo.DrbdRscPojo.DrbdVlmPojo;
import com.linbit.linstor.api.protobuf.ProtoMapUtils;
import com.linbit.linstor.event.EventIdentifier;
import com.linbit.linstor.event.common.UsageState;
import com.linbit.linstor.logging.ErrorReport;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.proto.common.FilterOuterClass;
import com.linbit.linstor.proto.common.NetInterfaceOuterClass;
import com.linbit.linstor.proto.common.NodeOuterClass;
import com.linbit.linstor.proto.common.RscConnOuterClass;
import com.linbit.linstor.proto.common.RscDfnOuterClass;
import com.linbit.linstor.proto.common.RscLayerDataOuterClass;
import com.linbit.linstor.proto.common.RscLayerDataOuterClass.RscLayerData;
import com.linbit.linstor.proto.common.RscOuterClass;
import com.linbit.linstor.proto.common.StorPoolDfnOuterClass;
import com.linbit.linstor.proto.common.StorPoolFreeSpaceOuterClass.StorPoolFreeSpace;
import com.linbit.linstor.proto.common.StorageRscOuterClass.DrbdDisklessVlm;
import com.linbit.linstor.proto.common.StorageRscOuterClass.LvmThinVlm;
import com.linbit.linstor.proto.common.StorageRscOuterClass.LvmVlm;
import com.linbit.linstor.proto.common.StorageRscOuterClass.StorageRsc;
import com.linbit.linstor.proto.common.StorageRscOuterClass.StorageVlm;
import com.linbit.linstor.proto.common.StorageRscOuterClass.SwordfishInitiator;
import com.linbit.linstor.proto.common.StorageRscOuterClass.SwordfishTarget;
import com.linbit.linstor.proto.common.StorageRscOuterClass.SwordfishVlmDfn;
import com.linbit.linstor.proto.common.StorageRscOuterClass.ZfsThinVlm;
import com.linbit.linstor.proto.common.StorageRscOuterClass.ZfsVlm;
import com.linbit.linstor.proto.common.VlmDfnOuterClass.VlmDfn;
import com.linbit.linstor.proto.common.StorPoolOuterClass;
import com.linbit.linstor.proto.common.VlmDfnOuterClass;
import com.linbit.linstor.proto.common.VlmOuterClass;
import com.linbit.linstor.proto.common.CryptRscOuterClass.CryptRsc;
import com.linbit.linstor.proto.common.CryptRscOuterClass.CryptVlm;
import com.linbit.linstor.proto.common.DrbdRscOuterClass.DrbdRsc;
import com.linbit.linstor.proto.common.DrbdRscOuterClass.DrbdRscDfn;
import com.linbit.linstor.proto.common.DrbdRscOuterClass.DrbdVlm;
import com.linbit.linstor.proto.common.DrbdRscOuterClass.DrbdVlmDfn;
import com.linbit.linstor.proto.common.RscConnOuterClass.RscConn;
import com.linbit.linstor.proto.common.VlmOuterClass.Vlm;
import com.linbit.linstor.proto.common.ApiCallResponseOuterClass;
import com.linbit.linstor.proto.MsgHeaderOuterClass;
import com.linbit.linstor.proto.apidata.VlmDfnApiData;
import com.linbit.linstor.proto.eventdata.EventRscStateOuterClass;
import com.linbit.linstor.proto.eventdata.EventVlmDiskStateOuterClass;
import com.linbit.linstor.proto.requests.MsgReqErrorReportOuterClass.MsgReqErrorReport;
import com.linbit.linstor.proto.responses.MsgErrorReportOuterClass.MsgErrorReport;
import com.linbit.linstor.proto.responses.MsgEventOuterClass;
import com.linbit.linstor.proto.responses.MsgHostnameOuterClass.MsgHostname;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.stateflags.FlagsHelper;
import com.linbit.linstor.storage.data.provider.swordfish.SfInitiatorData;
import com.linbit.linstor.storage.data.provider.swordfish.SfVlmDfnData;
import com.linbit.linstor.storage.interfaces.categories.RscLayerObject;

public class ProtoCommonSerializerBuilder implements CommonSerializer.CommonSerializerBuilder
{
    protected final ErrorReporter errorReporter;
    protected final AccessContext serializerCtx;
    protected final ByteArrayOutputStream baos;
    private boolean exceptionOccured;

    public ProtoCommonSerializerBuilder(
        final ErrorReporter errReporterRef,
        final AccessContext serializerCtxRef,
        String msgContent,
        Long apiCallId,
        boolean isAnswer
    )
    {
        this.errorReporter = errReporterRef;
        this.serializerCtx = serializerCtxRef;

        baos = new ByteArrayOutputStream();
        exceptionOccured = false;
        if (msgContent != null || apiCallId != null)

        {
            try
            {
                header(msgContent, apiCallId, isAnswer);
            }
            catch (IOException exc)
            {
                errorReporter.reportError(exc);
                exceptionOccured = true;
            }
        }
    }

    @Override
    public byte[] build()
    {
        byte[] ret;
        if (exceptionOccured)
        {
            ret = new byte[0]; // do not send corrupted data
        }
        else
        {
            ret = baos.toByteArray();
        }
        return ret;
    }

    private void header(String msgContent, Long apiCallId, boolean isAnswer) throws IOException
    {
        MsgHeaderOuterClass.MsgHeader.Builder headerBuilder = MsgHeaderOuterClass.MsgHeader.newBuilder();
        if (apiCallId == null)
        {
            headerBuilder
                .setMsgType(MsgHeaderOuterClass.MsgHeader.MsgType.ONEWAY)
                .setMsgContent(msgContent);
        }
        else if (msgContent == null)
        {
            headerBuilder
                .setMsgType(MsgHeaderOuterClass.MsgHeader.MsgType.COMPLETE)
                .setApiCallId(apiCallId);
        }
        else if (isAnswer)
        {
            headerBuilder
                .setMsgType(MsgHeaderOuterClass.MsgHeader.MsgType.ANSWER)
                .setApiCallId(apiCallId)
                .setMsgContent(msgContent);
        }
        else
        {
            headerBuilder
                .setMsgType(MsgHeaderOuterClass.MsgHeader.MsgType.API_CALL)
                .setApiCallId(apiCallId)
                .setMsgContent(msgContent);
        }
        headerBuilder
            .build()
            .writeDelimitedTo(baos);
    }

    protected void handleIOException(IOException exc)
    {
        errorReporter.reportError(exc);
        exceptionOccured = true;
    }

    protected void handleAccessDeniedException(AccessDeniedException accDeniedExc)
    {
        errorReporter.reportError(
            new ImplementationError(
                "ProtoInterComSerializer has not enough privileges to serialize node",
                accDeniedExc
            )
        );
        exceptionOccured = true;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder bytes(byte[] bytes)
    {
        try
        {
            baos.write(bytes);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder apiCallRcSeries(ApiCallRc apiCallRc)
    {
        try
        {
            for (ApiCallResponseOuterClass.ApiCallResponse protoMsg : serializeApiCallRc(apiCallRc))
            {
                protoMsg.writeDelimitedTo(baos);
            }
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder event(
        Integer watchId,
        EventIdentifier eventIdentifier,
        String eventStreamAction
    )
    {
        try
        {
            MsgEventOuterClass.MsgEvent.Builder eventBuilder = MsgEventOuterClass.MsgEvent.newBuilder();

            eventBuilder
                .setWatchId(watchId)
                .setEventAction(eventStreamAction)
                .setEventName(eventIdentifier.getEventName());

            if (eventIdentifier.getResourceName() != null)
            {
                eventBuilder.setResourceName(eventIdentifier.getResourceName().displayValue);
            }

            if (eventIdentifier.getNodeName() != null)
            {
                eventBuilder.setNodeName(eventIdentifier.getNodeName().displayValue);
            }

            if (eventIdentifier.getVolumeNumber() != null)
            {
                eventBuilder.setVolumeNumber(eventIdentifier.getVolumeNumber().value);
            }

            if (eventIdentifier.getSnapshotName() != null)
            {
                eventBuilder.setSnapshotName(eventIdentifier.getSnapshotName().displayValue);
            }

            eventBuilder.build().writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder volumeDiskState(String diskState)
    {
        try
        {
            EventVlmDiskStateOuterClass.EventVlmDiskState.newBuilder()
                .setDiskState(diskState)
                .build()
                .writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder resourceStateEvent(UsageState usageState)
    {
        try
        {
            EventRscStateOuterClass.EventRscState.Builder builder = EventRscStateOuterClass.EventRscState.newBuilder();
            if (usageState.getResourceReady() != null)
            {
                builder.setReady(usageState.getResourceReady());
            }
            if (usageState.getInUse() != null)
            {
                builder.setInUse(usageState.getInUse());
            }
            if (usageState.getUpToDate() != null)
            {
                builder.setUpToDate(usageState.getUpToDate());
            }

            builder.build().writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder requestErrorReports(
        Set<String> nodes,
        boolean withContent,
        Optional<Date> since,
        Optional<Date> to,
        Set<String> ids
    )
    {
        try
        {
            MsgReqErrorReport.Builder bld = MsgReqErrorReport.newBuilder();
            if (since.isPresent())
            {
                bld.setSince(since.get().getTime());
            }
            if (to.isPresent())
            {
                bld.setTo(to.get().getTime());
            }
            bld.addAllNodeNames(nodes).setWithContent(withContent).addAllIds(ids).build().writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder errorReports(Set<ErrorReport> errorReports)
    {
        try
        {
            for (ErrorReport errReport : errorReports)
            {
                MsgErrorReport.Builder msgErrorReport =
                    MsgErrorReport.newBuilder();

                msgErrorReport.setErrorTime(errReport.getDateTime().getTime());
                msgErrorReport.setNodeNames(errReport.getNodeName());
                msgErrorReport.setFilename(errReport.getFileName());
                if (!errReport.getText().isEmpty())
                {
                    msgErrorReport.setText(errReport.getText());
                }
                msgErrorReport.build().writeDelimitedTo(baos);
            }
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder hostName(String hostName)
    {
        try
        {
            MsgHostname msgHostname = MsgHostname.newBuilder().setHostname(hostName).build();
            msgHostname.writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    @Override
    public CommonSerializer.CommonSerializerBuilder filter(
        Set<NodeName> nodesFilter,
        Set<StorPoolName> storPoolFilter,
        Set<ResourceName> resourceFilter
    )
    {
        try
        {
            FilterOuterClass.Filter.newBuilder()
                .addAllNodeNames(
                    nodesFilter.stream().map(NodeName::getDisplayName).collect(Collectors.toList()))
                .addAllStorPoolNames(
                    storPoolFilter.stream().map(StorPoolName::getDisplayName).collect(Collectors.toList()))
                .addAllResourceNames(
                    resourceFilter.stream().map(ResourceName::getDisplayName).collect(Collectors.toList()))
                .build()
                .writeDelimitedTo(baos);
        }
        catch (IOException exc)
        {
            handleIOException(exc);
        }
        return this;
    }

    public static NodeOuterClass.Node serializeNode(
        AccessContext accCtx,
        Node node
    )
        throws AccessDeniedException
    {
        Map<String, String> nodeProps = node.getProps(accCtx).map();
        return NodeOuterClass.Node.newBuilder()
            .setUuid(node.getUuid().toString())
            .setName(node.getName().displayValue)
            .setType(node.getNodeType(accCtx).name())
            .addAllProps(ProtoMapUtils.fromMap(nodeProps))
            .addAllNetInterfaces(
                serializeNetInterfaces(
                    accCtx,
                    node.streamNetInterfaces(accCtx).collect(Collectors.toList())
                )
            )
            .build();
    }

    public static List<NetInterfaceOuterClass.NetInterface> serializeNetInterfaces(
        AccessContext accCtx,
        List<NetInterface> netIfs
    )
        throws AccessDeniedException
    {
        List<NetInterfaceOuterClass.NetInterface> protoNetIfList = new ArrayList<>(netIfs.size());
        for (NetInterface netIf : netIfs)
        {
            protoNetIfList.add(serializeNetInterface(accCtx, netIf));
        }
        return protoNetIfList;
    }

    public static NetInterfaceOuterClass.NetInterface serializeNetInterface(
        AccessContext accCtx,
        NetInterface netIf
    )
        throws AccessDeniedException
    {
        return NetInterfaceOuterClass.NetInterface.newBuilder()
            .setUuid(netIf.getUuid().toString())
            .setName(netIf.getName().displayValue)
            .setAddress(netIf.getAddress(accCtx).getAddress())
            .setStltPort(netIf.getStltConnPort(accCtx).value)
            .setStltEncryptionType(netIf.getStltConnEncryptionType(accCtx).name())
            .build();
    }


    public static RscDfnOuterClass.RscDfn serializeResourceDefinition(
        AccessContext accCtx,
        ResourceDefinition rscDfn
    )
        throws AccessDeniedException
    {
        return serializeResourceDefinition(rscDfn.getApiData(accCtx));
    }

    public static List<RscDfnOuterClass.RscDfn> serializeResourceDefinitions(
        List<ResourceDefinition.RscDfnApi> rscDfnApis
    )
    {
        ArrayList<RscDfnOuterClass.RscDfn> protoRscDfs = new ArrayList<>();
        for (ResourceDefinition.RscDfnApi rscDfnApi : rscDfnApis)
        {
            protoRscDfs.add(serializeResourceDefinition(rscDfnApi));
        }
        return protoRscDfs;
    }

    public static RscDfnOuterClass.RscDfn serializeResourceDefinition(
        ResourceDefinition.RscDfnApi rscDfnapi
    )
    {
        return RscDfnOuterClass.RscDfn.newBuilder()
            .setRscName(rscDfnapi.getResourceName())
            .setRscDfnPort(rscDfnapi.getPort())
            .setRscDfnSecret(rscDfnapi.getSecret())
            .setRscDfnUuid(rscDfnapi.getUuid().toString())
            .addAllVlmDfns(VlmDfnApiData.fromApiList(rscDfnapi.getVlmDfnList()))
            .addAllRscDfnProps(ProtoMapUtils.fromMap(rscDfnapi.getProps()))
            .build();
    }

    public static List<VlmDfnOuterClass.VlmDfn> serializeVolumeDefinition(
        List<VolumeDefinition> vlmDfnList,
        AccessContext accCtx
    )
        throws AccessDeniedException
    {
        List<VlmDfnOuterClass.VlmDfn> protoVlmDfnList = new ArrayList<>(vlmDfnList.size());
        for (VolumeDefinition vlmDfn : vlmDfnList)
        {
            protoVlmDfnList.add(serializeVolumeDefinition(vlmDfn, accCtx));
        }
        return protoVlmDfnList;
    }

    public static VlmDfnOuterClass.VlmDfn serializeVolumeDefinition(
        VolumeDefinition vlmDfn,
        AccessContext accCtx
    )
        throws AccessDeniedException
    {
        return VlmDfnOuterClass.VlmDfn.newBuilder()
            .setVlmDfnUuid(vlmDfn.getUuid().toString())
            .setVlmNr(vlmDfn.getVolumeNumber().value)
            .setVlmSize(vlmDfn.getVolumeSize(accCtx))
            .setVlmMinor(vlmDfn.getMinorNr(accCtx).value)
            .addAllVlmFlags(
                FlagsHelper.toStringList(
                    VolumeDefinition.VlmDfnFlags.class,
                    vlmDfn.getFlags().getFlagsBits(accCtx)
                )
            )
            .addAllVlmProps(ProtoMapUtils.fromMap(vlmDfn.getProps(accCtx).map()))
            .build();
    }

    public static List<VlmDfnOuterClass.VlmDfn> serializeVolumeDefinition(
        List<VolumeDefinition.VlmDfnApi> vlmDfnApiList
    )
    {
        List<VlmDfnOuterClass.VlmDfn> protoVlmDfnList = new ArrayList<>(vlmDfnApiList.size());
        for (VlmDfnApi vlmDfnApi : vlmDfnApiList)
        {
            protoVlmDfnList.add(serializeVolumeDefinition(vlmDfnApi));
        }
        return protoVlmDfnList;
    }

    public static VlmDfnOuterClass.VlmDfn serializeVolumeDefinition(
        VolumeDefinition.VlmDfnApi vlmDfnApi
    )
    {
        VlmDfnOuterClass.VlmDfn.Builder builder = VlmDfn.newBuilder()
            .setVlmDfnUuid(vlmDfnApi.getUuid().toString())
            .setVlmSize(vlmDfnApi.getSize())
            .addAllVlmFlags(Volume.VlmFlags.toStringList(vlmDfnApi.getFlags()))
            .addAllVlmProps(ProtoMapUtils.fromMap(vlmDfnApi.getProps()));
        if (vlmDfnApi.getVolumeNr() != null)
        {
            builder.setVlmNr(vlmDfnApi.getVolumeNr());
        }
        if (vlmDfnApi.getMinorNr() != null)
        {
            builder.setVlmMinor(vlmDfnApi.getMinorNr());
        }
        return builder.build();
    }

    public static RscOuterClass.Rsc serializeResource(
        AccessContext accCtx,
        Resource rsc
    )
        throws AccessDeniedException
    {
        return RscOuterClass.Rsc.newBuilder()
            .setUuid(rsc.getUuid().toString())
            .setName(rsc.getDefinition().getName().displayValue)
            .setNodeUuid(rsc.getAssignedNode().getUuid().toString())
            .setNodeName(rsc.getAssignedNode().getName().displayValue)
            .setRscDfnUuid(rsc.getDefinition().getUuid().toString())
            .addAllProps(ProtoMapUtils.fromMap(rsc.getProps(accCtx).map()))
            .addAllRscFlags(Resource.RscFlags.toStringList(rsc.getStateFlags().getFlagsBits(accCtx)))
            .addAllVlms(serializeVolumeList(accCtx, rsc.streamVolumes().collect(Collectors.toList())))
            .setNodeId(rsc.getNodeId().value)
            .setLayerObject(LayerObjectSerializer.serializeLayerObject(rsc.getLayerData(accCtx), accCtx))
            .build();
    }

    public static RscOuterClass.Rsc serializeResource(
        Resource.RscApi rscApi
    )
    {
        return RscOuterClass.Rsc.newBuilder()
            .setUuid(rscApi.getUuid().toString())
            .setName(rscApi.getName())
            .setNodeUuid(rscApi.getNodeUuid().toString())
            .setNodeName(rscApi.getNodeName())
            .setRscDfnUuid(rscApi.getRscDfnUuid().toString())
            .addAllProps(ProtoMapUtils.fromMap(rscApi.getProps()))
            .addAllRscFlags(
                FlagsHelper.toStringList(
                    Resource.RscFlags.class,
                    rscApi.getFlags()
                )
            )
            .addAllVlms(serializeVolumeList(rscApi.getVlmList()))
            .setNodeId(rscApi.getLocalRscNodeId())
            .setLayerObject(LayerObjectSerializer.serializeLayerObject(rscApi.getLayerData()))
            .build();
    }

    public static List<RscConnOuterClass.RscConn> serializeResourceConnections(
        AccessContext accCtx,
        List<ResourceConnection> rscConList
    )
        throws AccessDeniedException
    {
        List<RscConnOuterClass.RscConn> list = new ArrayList<>();

        for (ResourceConnection rscConn : rscConList)
        {
            list.add(serializeResourceConnection(accCtx, rscConn));
        }
        return list;
    }

    public static RscConnOuterClass.RscConn serializeResourceConnection(
        AccessContext accCtx,
        ResourceConnection rscConn
    )
        throws AccessDeniedException
    {
        RscConn.Builder builder = RscConn.newBuilder()
            .setRscConnUuid(rscConn.getUuid().toString())
            .setNodeName1(rscConn.getSourceResource(accCtx).getAssignedNode().getName().displayValue)
            .setNodeName2(rscConn.getTargetResource(accCtx).getAssignedNode().getName().displayValue)
            .setRscName(rscConn.getSourceResource(accCtx).getDefinition().getName().displayValue)
            .setRsc1Uuid(rscConn.getSourceResource(accCtx).getUuid().toString())
            .setRsc2Uuid(rscConn.getTargetResource(accCtx).getUuid().toString())
            .addAllRscConnProps(ProtoMapUtils.fromMap(rscConn.getProps(accCtx).map()))
            .addAllRscConnFlags(
                FlagsHelper.toStringList(
                    ResourceConnection.RscConnFlags.class,
                    rscConn.getStateFlags().getFlagsBits(accCtx)
                )
            );
        TcpPortNumber port = rscConn.getPort(accCtx);
        if (port != null)
        {
            builder.setPort(port.value);
        }
        return builder.build();
    }

    public static StorPoolOuterClass.StorPool serializeStorPool(
        AccessContext accCtx,
        StorPool storPool
    )
        throws AccessDeniedException
    {
        // TODO try to call something like the following
        // return serializeStorPool(storPool.getApiData(null, null, accCtx, null, null))
        StorPoolOuterClass.StorPool.Builder builder = StorPoolOuterClass.StorPool.newBuilder()
            .setStorPoolUuid(storPool.getUuid().toString())
            .setNodeUuid(storPool.getNode().getUuid().toString())
            .setNodeName(storPool.getNode().getName().displayValue)
            .setStorPoolDfnUuid(storPool.getDefinition(accCtx).getUuid().toString())
            .setStorPoolName(storPool.getName().displayValue)
            .setDriver(storPool.getDriverName())
            .addAllProps(ProtoMapUtils.fromMap(storPool.getProps(accCtx).map()))
            .addAllVlms(serializeVolumeList(accCtx, storPool.getVolumes(accCtx)))
            .addAllStaticTraits(ProtoMapUtils.fromMap(storPool.getDriverKind().getStaticTraits()));
        FreeSpaceTracker freeSpaceTracker = storPool.getFreeSpaceTracker();
        if (freeSpaceTracker.getTotalCapacity(accCtx).isPresent())
        {
            builder.setFreeSpace(
                StorPoolFreeSpace.newBuilder()
                    .setStorPoolName(storPool.getName().displayValue)
                    .setStorPoolUuid(storPool.getUuid().toString())
                    .setFreeCapacity(freeSpaceTracker.getFreeCapacityLastUpdated(accCtx).get())
                    .setTotalCapacity(freeSpaceTracker.getTotalCapacity(accCtx).get()
                )
                .build()
            );
        }
        return builder
            .setFreeSpaceMgrName(freeSpaceTracker.getName().displayValue)
            .build();
    }

    public static StorPoolOuterClass.StorPool serializeStorPool(
        final StorPool.StorPoolApi apiStorPool
    )
    {
        StorPoolOuterClass.StorPool.Builder storPoolBld = StorPoolOuterClass.StorPool.newBuilder()
            .setStorPoolName(apiStorPool.getStorPoolName())
            .setStorPoolUuid(apiStorPool.getStorPoolUuid().toString())
            .setNodeName(apiStorPool.getNodeName())
            .setNodeUuid(apiStorPool.getNodeUuid().toString())
            .setStorPoolDfnUuid(apiStorPool.getStorPoolDfnUuid().toString())
            .setDriver(apiStorPool.getDriver())
            .addAllProps(ProtoMapUtils.fromMap(apiStorPool.getStorPoolProps()))
            .addAllVlms(serializeVolumeList(apiStorPool.getVlmList()))
            .addAllStaticTraits(ProtoMapUtils.fromMap(apiStorPool.getStorPoolStaticTraits()))
            .setFreeSpaceMgrName(apiStorPool.getFreeSpaceManagerName());
        if (apiStorPool.getFreeCapacity().isPresent())
        {
            storPoolBld.setFreeSpace(
                StorPoolFreeSpace.newBuilder()
                    .setStorPoolName(apiStorPool.getStorPoolName())
                    .setStorPoolUuid(apiStorPool.getStorPoolUuid().toString())
                    .setFreeCapacity(apiStorPool.getFreeCapacity().get())
                    .setTotalCapacity(apiStorPool.getTotalCapacity().get()
                )
                .build()
            );
        }

        return storPoolBld.build();
    }

    public static StorPoolDfnOuterClass.StorPoolDfn serializeStorPoolDfn(
        AccessContext accCtx,
        StorPoolDefinition storPoolDfn
    )
        throws AccessDeniedException
    {
        return StorPoolDfnOuterClass.StorPoolDfn.newBuilder()
            .setStorPoolName(storPoolDfn.getName().displayValue)
            .setUuid(storPoolDfn.getUuid().toString())
            .addAllProps(ProtoMapUtils.fromMap(storPoolDfn.getProps(accCtx).map()))
            .build();
    }

    public static StorPoolDfnOuterClass.StorPoolDfn serializeStorPoolDfn(
        StorPoolDefinition.StorPoolDfnApi storPoolDfnApi
    )
    {
        return StorPoolDfnOuterClass.StorPoolDfn.newBuilder()
            .setStorPoolName(storPoolDfnApi.getName())
            .setUuid(storPoolDfnApi.getUuid().toString())
            .addAllProps(ProtoMapUtils.fromMap(storPoolDfnApi.getProps()))
            .build();
    }

    public static Iterable<? extends Vlm> serializeVolumeList(
        AccessContext accCtxRef,
        Collection<Volume> volumesRef
    )
        throws AccessDeniedException
    {
        List<VlmApi> vlmApiList = new ArrayList<>(volumesRef.size());
        for (Volume vlm : volumesRef)
        {
            vlmApiList.add(
                vlm.getApiData(
                    vlm.isAllocatedSizeSet(accCtxRef) ?
                        vlm.getAllocatedSize(accCtxRef) :
                        null,
                    accCtxRef
                )
            );
        }
        return serializeVolumeList(vlmApiList);
    }

    private static List<VlmOuterClass.Vlm> serializeVolumeList(List<? extends VlmApi> vlmApiList)
    {
        List<VlmOuterClass.Vlm> protoVlmList = new ArrayList<>(vlmApiList.size());
        for (VlmApi vlmApi : vlmApiList)
        {
            Vlm.Builder builder = Vlm.newBuilder()
                .setVlmUuid(vlmApi.getVlmUuid().toString())
                .setVlmDfnUuid(vlmApi.getVlmDfnUuid().toString())
                .setVlmNr(vlmApi.getVlmNr())
                .setVlmMinorNr(vlmApi.getVlmMinorNr())
                .setStorPoolUuid(vlmApi.getStorPoolUuid().toString())
                .setStorPoolName(vlmApi.getStorPoolName())
                .addAllVlmFlags(Volume.VlmFlags.toStringList(vlmApi.getFlags()))
                .addAllVlmProps(ProtoMapUtils.fromMap(vlmApi.getVlmProps()))
                .setStorPoolDriverName(vlmApi.getStorDriverSimpleClassName())
                .setStorPoolDfnUuid(vlmApi.getStorPoolDfnUuid().toString())
                .addAllStorPoolDfnProps(ProtoMapUtils.fromMap(vlmApi.getStorPoolDfnProps()))
                .addAllStorPoolProps(ProtoMapUtils.fromMap(vlmApi.getStorPoolProps()));

            if (vlmApi.getBlockDevice() != null)
            {
                builder.setBackingDisk(vlmApi.getBlockDevice());
            }
            if (vlmApi.getMetaDisk() != null)
            {
                builder.setMetaDisk(vlmApi.getMetaDisk());
            }
            if (vlmApi.getDevicePath() != null)
            {
                builder.setDevicePath(vlmApi.getDevicePath());
            }
            vlmApi.getAllocatedSize().ifPresent(builder::setAllocatedSize);
            vlmApi.getUsableSize().ifPresent(builder::setUsableSize);

            protoVlmList.add(builder.build());
        }
        return protoVlmList;
    }

    public static List<ApiCallResponseOuterClass.ApiCallResponse> serializeApiCallRc(ApiCallRc apiCallRc)
    {
        List<ApiCallResponseOuterClass.ApiCallResponse> list = new ArrayList<>();

        for (ApiCallRc.RcEntry apiCallEntry : apiCallRc.getEntries())
        {
            ApiCallResponseOuterClass.ApiCallResponse.Builder msgApiCallResponseBuilder =
                ApiCallResponseOuterClass.ApiCallResponse.newBuilder();

            msgApiCallResponseBuilder.setRetCode(apiCallEntry.getReturnCode());
            if (apiCallEntry.getCause() != null)
            {
                msgApiCallResponseBuilder.setCause(apiCallEntry.getCause());
            }
            if (apiCallEntry.getCorrection() != null)
            {
                msgApiCallResponseBuilder.setCorrection(apiCallEntry.getCorrection());
            }
            if (apiCallEntry.getDetails() != null)
            {
                msgApiCallResponseBuilder.setDetails(apiCallEntry.getDetails());
            }
            if (apiCallEntry.getMessage() != null)
            {
                msgApiCallResponseBuilder.setMessage(apiCallEntry.getMessage());
            }
            msgApiCallResponseBuilder.addAllErrorReportIds(apiCallEntry.getErrorIds());
            msgApiCallResponseBuilder.addAllObjRefs(ProtoMapUtils.fromMap(apiCallEntry.getObjRefs()));

            ApiCallResponseOuterClass.ApiCallResponse protoMsg = msgApiCallResponseBuilder.build();

            list.add(protoMsg);
        }

        return list;
    }

    public static class LayerObjectSerializer
    {
        public static RscLayerDataOuterClass.RscLayerData serializeLayerObject(
            RscLayerObject layerData,
            AccessContext accCtx
        )
            throws AccessDeniedException
        {
            return serializeLayerObject(layerData.asPojo(accCtx));
        }

        public static RscLayerDataOuterClass.RscLayerData serializeLayerObject(
            RscLayerDataPojo rscLayerPojo
        )
        {
            RscLayerData.Builder builder = RscLayerData.newBuilder();

            List<RscLayerData> serializedChildren = new ArrayList<>();
            for (RscLayerDataPojo childRscObj : rscLayerPojo.getChildren())
            {
                serializedChildren.add(serializeLayerObject(childRscObj));
            }
            builder
                .setId(rscLayerPojo.getId())
                .setRscNameSuffix(rscLayerPojo.getRscNameSuffix())
                .addAllChildren(serializedChildren);
            switch (rscLayerPojo.getLayerKind())
            {
                case DRBD:
                    builder.setDrbd(buildDrbdRscData((DrbdRscPojo) rscLayerPojo));
                    break;
                case CRYPT_SETUP:
                    builder.setCrypt(buildCryptRscData((CryptSetupRscPojo) rscLayerPojo));
                    break;
                case STORAGE:
                    builder.setStorage(buildStorageRscData((StorageRscPojo) rscLayerPojo));
                    break;
                default:
                    break;
            }
            return builder.build();
        }

        private static DrbdRsc buildDrbdRscData(DrbdRscPojo drbdRscPojo)
        {
            DrbdRscDfnPojo drbdRscDfnPojo = drbdRscPojo.getDrbdRscDfn();

            List<DrbdVlm> serializedDrbdVlms = new ArrayList<>();
            for (DrbdVlmPojo drbdVlmPojo : drbdRscPojo.getVolumeList())
            {
                DrbdVlmDfnPojo drbdVlmDfnPojo = drbdVlmPojo.getDrbdVlmDfn();
                DrbdVlm.Builder builder = DrbdVlm.newBuilder()
                    .setDrbdVlmDfn(
                        DrbdVlmDfn.newBuilder()
                            .setRscNameSuffix(drbdVlmDfnPojo.getRscNameSuffix())
                            .setVlmNr(drbdVlmDfnPojo.getVlmNr())
                            .setMinor(drbdVlmDfnPojo.getMinorNr())
                    )
                    .setAllocatedSize(drbdVlmPojo.getAllocatedSize())
                    .setUsableSize(drbdVlmPojo.getUsableSize());
                if (drbdVlmPojo.getDevicePath() != null)
                {
                    builder.setDevicePath(drbdVlmPojo.getDevicePath());
                }
                if (drbdVlmPojo.getBackingDisk() != null)
                {
                    builder.setBackingDevice(drbdVlmPojo.getBackingDisk());
                }
                if (drbdVlmPojo.getMetaDisk() != null)
                {
                    builder.setMetaDisk(drbdVlmPojo.getMetaDisk());
                }

                serializedDrbdVlms.add(
                    builder.build()
                );
            }

            DrbdRsc.Builder builder = DrbdRsc.newBuilder();
            builder.setDrbdRscDfn(
                DrbdRscDfn.newBuilder()
                    .setRscNameSuffix(drbdRscDfnPojo.getRscNameSuffix())
                    .setPeersSlots(drbdRscDfnPojo.getPeerSlots())
                    .setAlStripes(drbdRscDfnPojo.getAlStripes())
                    .setAlSize(drbdRscDfnPojo.getAlStripeSize())
                    .setPort(drbdRscDfnPojo.getPort())
                    .setTransportType(drbdRscDfnPojo.getTransportType())
                    .setSecret(drbdRscDfnPojo.getSecret())
                    .build()
                )
                .setNodeId(drbdRscPojo.getNodeId())
                .setPeersSlots(drbdRscPojo.getPeerSlots())
                .setAlStripes(drbdRscPojo.getAlStripes())
                .setAlSize(drbdRscPojo.getAlStripeSize())
                .setFlags(drbdRscPojo.getFlags())
                .addAllDrbdVlms(serializedDrbdVlms);

            return builder.build();
        }

        private static CryptRsc buildCryptRscData(CryptSetupRscPojo rscLayerPojoRef)
        {
            List<CryptVlm> cryptVlms = new ArrayList<>();
            for (CryptVlmPojo cryptSetupVlmPojo : rscLayerPojoRef.getVolumeList())
            {
                CryptVlm.Builder builder = CryptVlm.newBuilder()
                    .setVlmNr(cryptSetupVlmPojo.getVlmNr())
                    .setEncryptedPassword(ByteString.copyFrom(cryptSetupVlmPojo.getEncryptedPassword()))
                    .setAllocatedSize(cryptSetupVlmPojo.getAllocatedSize())
                    .setUsableSize(cryptSetupVlmPojo.getUsableSize());
                if (cryptSetupVlmPojo.getDevicePath() != null)
                {
                    builder.setDevicePath(cryptSetupVlmPojo.getDevicePath());
                }
                if (cryptSetupVlmPojo.getBackingDevice() != null)
                {
                    builder.setBackingDevice(cryptSetupVlmPojo.getBackingDevice());
                }

                cryptVlms.add(
                    builder.build()
                );
            }

            return CryptRsc.newBuilder()
                .addAllCryptVlms(cryptVlms)
                .build();
        }

        private static StorageRsc buildStorageRscData(StorageRscPojo rscLayerPojoRef)
        {
            List<StorageVlm> storageVlms = new ArrayList<>();
            for (VlmLayerDataPojo vlmPojo : rscLayerPojoRef.getVolumeList())
            {
                StorageVlm.Builder builder = StorageVlm.newBuilder()
                    .setVlmNr(vlmPojo.getVlmNr())
                    .setAllocatedSize(vlmPojo.getAllocatedSize())
                    .setUsableSize(vlmPojo.getUsableSize());
                if (vlmPojo.getDevicePath() != null)
                {
                    builder.setDevicePath(vlmPojo.getDevicePath());
                }

                switch (vlmPojo.getProviderKind())
                {
                    case DRBD_DISKLESS:
                        builder.setDrbdDiskless(DrbdDisklessVlm.newBuilder().build());
                        break;
                    case LVM:
                        builder.setLvm(LvmVlm.newBuilder().build());
                        break;
                    case LVM_THIN:
                        builder.setLvmThin(LvmThinVlm.newBuilder().build());
                        break;
                    case ZFS:
                        builder.setZfs(ZfsVlm.newBuilder().build());
                        break;
                    case ZFS_THIN:
                        builder.setZfsThin(ZfsThinVlm.newBuilder().build());
                        break;
                    case SWORDFISH_INITIATOR:
                        {
                            SfVlmDfnData sfVlmDfnData = ((SfInitiatorData) vlmPojo).getVlmDfnLayerObject();
                            builder.setSfInit(
                                SwordfishInitiator.newBuilder()
                                    .setSfVlmDfn(
                                        SwordfishVlmDfn.newBuilder()
                                            .setRscNameSuffix(sfVlmDfnData.getRscNameSuffix())
                                            .setVlmNr(sfVlmDfnData.getVolumeDefinition().getVolumeNumber().value)
                                            .setVlmOdata(sfVlmDfnData.getVlmOdata())
                                            .build()
                                    )
                                    .build()
                            );
                        }
                        break;
                    case SWORDFISH_TARGET:
                        {
                            SfVlmDfnData sfVlmDfnData = ((SfInitiatorData) vlmPojo).getVlmDfnLayerObject();
                            builder.setSfTarget(
                                SwordfishTarget.newBuilder()
                                    .setSfVlmDfn(
                                        SwordfishVlmDfn.newBuilder()
                                            .setRscNameSuffix(sfVlmDfnData.getRscNameSuffix())
                                            .setVlmNr(sfVlmDfnData.getVolumeDefinition().getVolumeNumber().value)
                                            .setVlmOdata(sfVlmDfnData.getVlmOdata())
                                            .build()
                                    )
                                    .build()
                                );
                        }
                        break;
                    case FAIL_BECAUSE_NOT_A_VLM_PROVIDER_BUT_A_VLM_LAYER:
                    default:
                        throw new ImplementationError("Unexpected provider kind: " + vlmPojo.getProviderKind());
                }
                storageVlms.add(builder.build());
            }
            return StorageRsc.newBuilder()
                .addAllStorageVlms(storageVlms)
                .build();
        }
    }
}
