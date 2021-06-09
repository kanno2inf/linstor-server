package com.linbit.linstor.layer.drbd.utils;

import com.linbit.ImplementationError;
import com.linbit.InvalidNameException;
import com.linbit.linstor.InternalApiConsts;
import com.linbit.linstor.LinStorException;
import com.linbit.linstor.LinStorRuntimeException;
import com.linbit.linstor.PriorityProps;
import com.linbit.linstor.PriorityProps.MultiResult;
import com.linbit.linstor.PriorityProps.ValueWithDescription;
import com.linbit.linstor.api.ApiConsts;
import com.linbit.linstor.api.prop.LinStorObject;
import com.linbit.linstor.api.prop.WhitelistProps;
import com.linbit.linstor.core.LinStor;
import com.linbit.linstor.core.identifier.NetInterfaceName;
import com.linbit.linstor.core.identifier.VolumeNumber;
import com.linbit.linstor.core.objects.NetInterface;
import com.linbit.linstor.core.objects.Node;
import com.linbit.linstor.core.objects.NodeConnection;
import com.linbit.linstor.core.objects.Resource;
import com.linbit.linstor.core.objects.ResourceConnection;
import com.linbit.linstor.core.objects.ResourceDefinition;
import com.linbit.linstor.core.objects.ResourceGroup;
import com.linbit.linstor.core.objects.Volume;
import com.linbit.linstor.core.objects.VolumeDefinition;
import com.linbit.linstor.core.types.LsIpAddress;
import com.linbit.linstor.core.types.TcpPortNumber;
import com.linbit.linstor.logging.ErrorReporter;
import com.linbit.linstor.propscon.InvalidKeyException;
import com.linbit.linstor.propscon.Props;
import com.linbit.linstor.security.AccessContext;
import com.linbit.linstor.security.AccessDeniedException;
import com.linbit.linstor.storage.StorageException;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscData;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdRscDfnData;
import com.linbit.linstor.storage.data.adapter.drbd.DrbdVlmData;
import com.linbit.linstor.storage.interfaces.categories.resource.AbsRscLayerObject;
import com.linbit.linstor.storage.interfaces.categories.resource.VlmProviderObject;
import com.linbit.linstor.storage.kinds.DeviceLayerKind;
import com.linbit.linstor.storage.utils.LayerUtils;
import com.linbit.utils.Pair;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.slf4j.event.Level;

public class ConfFileBuilder
{
    private static final ResourceNameComparator RESOURCE_NAME_COMPARATOR = new ResourceNameComparator();

    private final ErrorReporter errorReporter;
    private final AccessContext accCtx;
    private final DrbdRscData<Resource> localRscData;
    private final Collection<DrbdRscData<Resource>> remoteResourceData;
    private final WhitelistProps whitelistProps;
    private final Props stltProps;

    private final StringBuilder stringBuilder;
    private int indentDepth;

    public ConfFileBuilder(
        final ErrorReporter errorReporterRef,
        final AccessContext accCtxRef,
        final DrbdRscData<Resource> localRscRef,
        final Collection<DrbdRscData<Resource>> remoteResourcesRef,
        final WhitelistProps whitelistPropsRef,
        final Props stltPropsRef
    )
    {
        errorReporter = errorReporterRef;
        accCtx = accCtxRef;
        localRscData = localRscRef;
        remoteResourceData = remoteResourcesRef;
        whitelistProps = whitelistPropsRef;
        stltProps = stltPropsRef;

        stringBuilder = new StringBuilder();
        indentDepth = 0;
    }

    private String header() throws InvalidKeyException, AccessDeniedException
    {
        StringBuilder sb = new StringBuilder();
        sb.append(
            String.format(
                "# This file was generated by linstor(%s), do not edit manually.",
                LinStor.VERSION_INFO_PROVIDER.getVersion()
            )
        );

        if (localRscData != null)
        {
            sb.append("\n# Local node: ")
                .append(localRscData.getAbsResource().getNode().getName().displayValue)
                .append("\n# Host name : ")
                .append(localRscData.getAbsResource().getNode().getProps(accCtx).getProp(InternalApiConsts.NODE_UNAME));
        }

        return sb.toString();
    }

    public String build()
        throws AccessDeniedException, StorageException
    {
        Set<DrbdRscData<Resource>> peerRscSet = new TreeSet<>(RESOURCE_NAME_COMPARATOR);
        DrbdRscDfnData<Resource> rscDfnData = localRscData.getRscDfnLayerObject();
        if (remoteResourceData == null)
        {
            throw new ImplementationError("No remote resources found for " + localRscData.getAbsResource() + "!");
        }
        peerRscSet.addAll(remoteResourceData); // node-alphabetically sorted

        Resource localRsc = localRscData.getAbsResource();
        final ResourceDefinition rscDfn = localRsc.getDefinition();
        if (rscDfn == null)
        {
            throw new ImplementationError("No resource definition found for " + localRsc + "!");
        }
        final Props localRscProps = localRsc.getProps(accCtx);
        final Props rscDfnProps = rscDfn.getProps(accCtx);
        final ResourceGroup rscGrp = rscDfn.getResourceGroup();
        final Props rscGrpProps = rscGrp.getProps(accCtx);
        final String localNodeName = localRscData.getAbsResource().getNode().getName().displayValue;

        appendLine(header());
        appendLine("");
        appendLine("resource \"%s\"", localRscData.getSuffixedResourceName());
        try (Section resourceSection = new Section())
        {
            PriorityProps prioProps = new PriorityProps()
                .addProps(localRscProps, "R (" + rscDfn.getName() + ")")
                .addProps(rscDfnProps, "RD (" + rscDfn.getName() + ")")
                .addProps(rscGrpProps, "RG (" + rscGrp.getName() + ")")
                .addProps(localRsc.getNode().getProps(accCtx), "N (" + localNodeName + ")")
                .addProps(stltProps, "C");

            // set auto verify algorithm if none is set yet by the user
            final String verifyAlgo = prioProps.getProp(
                InternalApiConsts.DRBD_VERIFY_ALGO, ApiConsts.NAMESPC_DRBD_NET_OPTIONS);
            final String autoVerifyAlgo = prioProps.getProp(
                InternalApiConsts.DRBD_AUTO_VERIFY_ALGO, ApiConsts.NAMESPC_DRBD_OPTIONS);
            if (verifyAlgo == null && autoVerifyAlgo != null) {
                prioProps.setFallbackProp(
                    InternalApiConsts.DRBD_VERIFY_ALGO,
                    autoVerifyAlgo,
                    ApiConsts.NAMESPC_DRBD_NET_OPTIONS);
            }

            if (prioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_HANDLER_OPTIONS))
            {
                appendLine("");
                appendLine("handlers");
                try (Section optionsSection = new Section())
                {
                    appendConflictingDrbdOptions(
                        LinStorObject.CONTROLLER,
                        ApiConsts.NAMESPC_DRBD_HANDLER_OPTIONS,
                        prioProps
                    );
                }
            }

            if (prioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_RESOURCE_OPTIONS))
            {
                appendLine("");
                appendLine("options");
                try (Section optionsSection = new Section())
                {
                    appendConflictingDrbdOptions(
                        LinStorObject.CONTROLLER,
                        ApiConsts.NAMESPC_DRBD_RESOURCE_OPTIONS,
                        prioProps
                    );
                }
            }

            appendLine("");
            appendLine("net");
            try (Section netSection = new Section())
            {
                // TODO: make configurable
                appendLine("cram-hmac-alg     %s;", "sha1");
                // TODO: make configurable
                appendLine("shared-secret     \"%s\";", rscDfnData.getSecret());

                appendConflictingDrbdOptions(
                    LinStorObject.CONTROLLER,
                    ApiConsts.NAMESPC_DRBD_NET_OPTIONS,
                    prioProps
                );
            }

            if (prioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_DISK_OPTIONS))
            {
                appendLine("");
                appendLine("disk");
                try (Section ignore = new Section())
                {
                    appendConflictingDrbdOptions(
                        LinStorObject.CONTROLLER,
                        ApiConsts.NAMESPC_DRBD_DISK_OPTIONS,
                        prioProps
                    );
                }
            }

            int port = rscDfnData.getTcpPort().value;
            // Create local network configuration
            {
                appendLine("");
                appendLine("on %s", localRsc.getNode().getProps(accCtx).getPropWithDefault(
                    InternalApiConsts.NODE_UNAME,
                    localRsc.getNode().getName().displayValue)
                );
                try (Section onSection = new Section())
                {
                    Collection<DrbdVlmData<Resource>> vlmDataList = localRscData.getVlmLayerObjects().values();
                    for (DrbdVlmData<Resource> vlmData : vlmDataList)
                    {
                        appendVlmIfPresent(vlmData, accCtx, false);
                    }
                    appendLine("node-id    %d;", localRscData.getNodeId().value);
                }
            }

            for (final DrbdRscData<Resource> peerRscData : peerRscSet)
            {
                Resource peerRsc = peerRscData.getAbsResource();
                if (peerRsc.getStateFlags().isUnset(accCtx, Resource.Flags.DELETE))
                {
                    appendLine("");
                    appendLine("on %s", peerRsc.getNode().getProps(accCtx)
                        .getPropWithDefault(
                            InternalApiConsts.NODE_UNAME,
                            peerRsc.getNode().getName().displayValue)
                    );
                    try (Section onSection = new Section())
                    {
                        Collection<DrbdVlmData<Resource>> peerVlmDataList = peerRscData
                            .getVlmLayerObjects().values();
                        for (DrbdVlmData<Resource> peerVlmData : peerVlmDataList)
                        {
                            appendVlmIfPresent(peerVlmData, accCtx, true);
                        }

                        appendLine("node-id    %d;", peerRscData.getNodeId().value);

                        // TODO: implement "multi-connection / path magic" (nodeMeshes + singleConnections vars)
                        // sb.append(peerResource.co)
                    }
                }
            }

            // first generate all with local first
            for (final DrbdRscData<Resource> peerRscData : peerRscSet)
            {
                Resource peerRsc = peerRscData.getAbsResource();
                // don't create a connection entry if the resource has the deleted flag
                // or if it is a connection between two diskless nodes
                if (
                    peerRsc.getStateFlags().isUnset(accCtx, Resource.Flags.DELETE) &&
                        !(peerRsc.disklessForDrbdPeers(accCtx) &&
                            localRsc.getStateFlags().isSet(accCtx, Resource.Flags.DRBD_DISKLESS))
                )
                {
                    appendLine("");
                    appendLine("connection");
                    try (Section connectionSection = new Section())
                    {
                        List<Pair<NetInterface, NetInterface>> pathsList = new ArrayList<>();
                        ResourceConnection rscConn = localRsc.getAbsResourceConnection(accCtx, peerRsc);
                        NodeConnection nodeConn;
                        Optional<Props> paths = Optional.empty();

                        if (rscConn != null)
                        {
                            // get paths from resource connection...
                            paths = rscConn.getProps(accCtx).getNamespace(ApiConsts.NAMESPC_CONNECTION_PATHS);

                            Props rscConnProps = rscConn.getProps(accCtx);
                            if (rscConnProps.getNamespace(ApiConsts.NAMESPC_DRBD_NET_OPTIONS).isPresent())
                            {
                                appendLine("");
                                appendLine("net");
                                try (Section ignore = new Section())
                                {
                                    appendDrbdOptions(
                                        LinStorObject.CONTROLLER,
                                        rscConnProps,
                                        ApiConsts.NAMESPC_DRBD_NET_OPTIONS
                                    );
                                }
                            }

                            if (rscConnProps.getNamespace(ApiConsts.NAMESPC_DRBD_PEER_DEVICE_OPTIONS).isPresent())
                            {
                                appendLine("");
                                appendLine("disk");
                                try (Section ignore = new Section())
                                {
                                    appendConflictingDrbdOptions(
                                        LinStorObject.CONTROLLER,
                                        ApiConsts.NAMESPC_DRBD_PEER_DEVICE_OPTIONS,
                                        new PriorityProps()
                                            .addProps(
                                                rscConnProps,
                                                String.format(
                                                    "Resource connection(%s <-> %s)",
                                                    rscConn.getSourceResource(accCtx),
                                                    rscConn.getTargetResource(accCtx)
                                                )
                                            )
                                            .addProps(
                                                rscDfnProps,
                                                String.format(
                                                    "Resource definition (%s)",
                                                    rscDfn.getName()
                                                )
                                            )
                                            .addProps(
                                                rscGrpProps,
                                                String.format(
                                                    "Resource group (%s)",
                                                    rscGrp.getName()
                                                )
                                            )
                                    );
                                }
                            }
                        }
                        else
                        {
                            if (prioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_PEER_DEVICE_OPTIONS))
                            {
                                appendLine("");
                                appendLine("disk");
                                try (Section ignore = new Section())
                                {
                                    appendConflictingDrbdOptions(
                                        LinStorObject.CONTROLLER,
                                        ApiConsts.NAMESPC_DRBD_PEER_DEVICE_OPTIONS,
                                        prioProps
                                    );
                                }
                            }
                        }

                        // ...or fall back to node connection
                        if (!paths.isPresent())
                        {
                            nodeConn = localRsc.getNode().getNodeConnection(accCtx, peerRsc.getNode());

                            if (nodeConn != null)
                            {
                                paths = nodeConn.getProps(accCtx).getNamespace(ApiConsts.NAMESPC_CONNECTION_PATHS);
                            }
                        }

                        if (paths.isPresent())
                        {
                            // iterate through network connection paths
                            Iterator<String> pathsIterator = paths.get().iterateNamespaces();
                            while (pathsIterator.hasNext())
                            {
                                String path = pathsIterator.next();
                                Optional<Props> nodes = paths.get().getNamespace(path);

                                if (nodes.isPresent() && nodes.get().map().size() == 2)
                                {
                                    Node firstNode = peerRsc.getNode();
                                    Node secondNode = localRsc.getNode();
                                    try
                                    {
                                        // iterate through nodes (should be exactly 2)
                                        Iterator<String> nodesIterator = nodes.get().keysIterator();
                                        String firstNodeName = nodesIterator.next().split("/")[2];
                                        String secondNodeName = nodesIterator.next().split("/")[2];

                                        // keep order of nodes correct
                                        if (firstNode.getName().value.equalsIgnoreCase(secondNodeName) &&
                                            secondNode.getName().value.equalsIgnoreCase(firstNodeName))
                                        {
                                            Node temp = firstNode;
                                            firstNode = secondNode;
                                            secondNode = temp;
                                        }
                                        else
                                        if (!(firstNode.getName().value.equalsIgnoreCase(firstNodeName) &&
                                                secondNode.getName().value.equalsIgnoreCase(secondNodeName)))
                                        {
                                            throw new ImplementationError(
                                                "Configured node names " + firstNodeName + " and " +
                                                secondNodeName + " do not match the actual node names."
                                            );
                                        }

                                        // get corresponding network interfaces
                                        String nicName = nodes.get().getProp(firstNodeName);
                                        NetInterface firstNic = firstNode.getNetInterface(
                                                accCtx, new NetInterfaceName(nicName));

                                        if (firstNic == null)
                                        {
                                            throw new StorageException("Network interface '" + nicName +
                                                "' of node '" + firstNode + "' does not exist!");
                                        }

                                        nicName = nodes.get().getProp(secondNodeName);
                                        NetInterface secondNic = secondNode.getNetInterface(
                                            accCtx, new NetInterfaceName(nicName));

                                        if (secondNic == null)
                                        {
                                            throw new StorageException("Network interface '" + nicName +
                                                "' of node '" + secondNode + "' does not exist!");
                                        }

                                        pathsList.add(new Pair<>(firstNic, secondNic));
                                    }
                                    catch (InvalidKeyException exc)
                                    {
                                        throw new ImplementationError(
                                            "No network interface configured!", exc);
                                    }
                                    catch (InvalidNameException exc)
                                    {
                                        throw new StorageException(
                                            "Name format of for network interface is not valid!", exc);
                                    }
                                }
                                else
                                {
                                    throw new ImplementationError(
                                        "When configuring a path it must contain exactly two nodes!");
                                }
                            }

                            // add network connection paths...
                            for (Pair<NetInterface, NetInterface> path : pathsList)
                            {
                                if (path != pathsList.get(0))
                                {
                                    appendLine("");
                                }
                                appendLine("path");
                                try (Section pathSection = new Section())
                                {
                                    appendConnectionHost(port, rscConn, path.objA);
                                    appendConnectionHost(port, rscConn, path.objB);
                                }
                            }
                        }
                        else
                        {
                            // ...or fall back to previous implementation
                            appendConnectionHost(port, rscConn, getPreferredNetIf(localRscData));
                            appendConnectionHost(port, rscConn, getPreferredNetIf(peerRscData));
                        }
                    }
                }
            }

            String compressionTypeProp = prioProps.getProp(
                ApiConsts.KEY_DRBD_PROXY_COMPRESSION_TYPE,
                ApiConsts.NAMESPC_DRBD_PROXY
            );

            if (prioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_PROXY_OPTIONS) ||
                compressionTypeProp != null)
            {
                appendLine("");
                appendLine("proxy");
                try (Section ignore = new Section())
                {
                    appendConflictingDrbdOptions(
                        LinStorObject.DRBD_PROXY,
                        ApiConsts.NAMESPC_DRBD_PROXY_OPTIONS,
                        prioProps
                    );

                    if (compressionTypeProp != null)
                    {
                        appendCompressionPlugin(rscDfn, compressionTypeProp);
                    }
                }
            }
        }

        return stringBuilder.toString();
    }

    private void appendConnectionHost(int rscDfnPort, ResourceConnection rscConn, NetInterface netIf)
        throws AccessDeniedException
    {
        TcpPortNumber rscConnPort = rscConn == null ? null : rscConn.getPort(accCtx);
        int port = rscConnPort == null ? rscDfnPort : rscConnPort.value;

        LsIpAddress addr = netIf.getAddress(accCtx);
        String addrText = addr.getAddress();

        String outsideAddress;
        if (addr.getAddressType() == LsIpAddress.AddrType.IPv6)
        {
            outsideAddress = String.format("ipv6 [%s]:%d", addrText, port);
        }
        else
        {
            outsideAddress = String.format("ipv4 %s:%d", addrText, port);
        }

        String hostName = netIf.getNode().getProps(accCtx).getPropWithDefault(
            InternalApiConsts.NODE_UNAME,
            netIf.getNode().getName().displayValue
        );

        if (rscConn != null && rscConn.getStateFlags().isSet(accCtx, ResourceConnection.Flags.LOCAL_DRBD_PROXY))
        {
            appendLine("host %s address 127.0.0.1:%d via proxy on %s", hostName, port, hostName);
            try (Section ignore = new Section())
            {
                appendLine("inside 127.0.0.2:%d;", port);
                appendLine("outside %s;", outsideAddress);
            }
        }
        else
        {
            appendLine("host %s address %s;", hostName, outsideAddress);
        }
    }

    private void appendCompressionPlugin(ResourceDefinition rscDfn, String compressionType)
        throws AccessDeniedException
    {
        appendLine("plugin");
        try (Section pluginSection = new Section())
        {
            String namespace = ApiConsts.NAMESPC_DRBD_PROXY_COMPRESSION_OPTIONS;

            List<String> compressionPluginTerms = new ArrayList<>();
            compressionPluginTerms.add(compressionType);

            Map<String, String> drbdProps = new PriorityProps(
                rscDfn.getProps(accCtx),
                rscDfn.getResourceGroup().getProps(accCtx),
                stltProps
            )
                .renderRelativeMap(namespace);

            for (Map.Entry<String, String> entry : drbdProps.entrySet())
            {
                String key = entry.getKey();
                String value = entry.getValue();
                if (checkValidDrbdOption(LinStorObject.drbdProxyCompressionObject(compressionType), key, value))
                {
                    compressionPluginTerms.add(key.substring(namespace.length() + 1));
                    compressionPluginTerms.add(value);
                }
            }

            appendLine("%s;", String.join(" ", compressionPluginTerms));
        }
    }

    private boolean checkValidDrbdOption(
        final LinStorObject lsObj,
        final String key,
        final String value
    )
    {
        boolean ret = true;
        if (!whitelistProps.isAllowed(lsObj, new ArrayList<>(), key, value, true))
        {
            ret = false;
            errorReporter.reportProblem(
                Level.WARN,
                new LinStorException(
                    "Ignoring property '" + key + "' with value '" + value + "' as it is not whitelisted."
                ),
                null,
                null,
                "The whitelist was generated from 'drbdsetup xml-help {resource,peer-device,net,disk}-options'" +
                    " when the satellite started."
            );
        }

        return ret;
    }

    private void appendConflictingDrbdOptions(
        final LinStorObject lsObj,
        final String namespace,
        final PriorityProps prioProps
    )
    {
        Map<String, MultiResult> map = prioProps.renderConflictingMap(namespace, true);
        int substrFrom = namespace.length() + 1;

        StringBuilder confLine = new StringBuilder();
        for (Entry<String, MultiResult> entry : map.entrySet())
        {
            confLine.setLength(0);

            appendIndent(confLine);

            String keyWithNamespace = entry.getKey();

            MultiResult multiResult = entry.getValue();
            String value = multiResult.first.value;

            if (checkValidDrbdOption(lsObj, keyWithNamespace, value))
            {
                final boolean quoteValue = whitelistProps.needsQuoting(lsObj, keyWithNamespace);
                confLine.append(
                    String.format(
                        quoteValue ? "%s \"%s\";" : "%s %s;",
                        keyWithNamespace.substring(substrFrom),
                        value
                    )
                );
                append(confLine.toString());

                if (!multiResult.conflictingList.isEmpty())
                {
                    int commentPos = confLine.length() + 1;
                    confLine.setLength(0);

                    char[] spacesChar = new char[commentPos];
                    Arrays.fill(spacesChar, ' ');
                    String spaces = new String(spacesChar);

                    for (ValueWithDescription valueWithDescription : multiResult.conflictingList)
                    {
                        append("%s# overrides value '%s' from %s%n",
                            spaces,
                            valueWithDescription.value,
                            valueWithDescription.propsDescription
                        );
                    }
                }
                else
                {
                    append("%n");
                }
            }
        }
    }

    private void appendDrbdOptions(
        final LinStorObject lsObj,
        final Props props,
        final String namespace
    )
    {
        Map<String, String> drbdProps = props.getNamespace(namespace)
            .map(Props::map).orElse(Collections.emptyMap());
        int substrFrom = namespace.length() + 1;

        for (Map.Entry<String, String> entry : drbdProps.entrySet())
        {
            String keyWithNamespace = entry.getKey();
            String value = entry.getValue();
            if (checkValidDrbdOption(lsObj, keyWithNamespace, value))
            {
                final boolean quote = whitelistProps.needsQuoting(lsObj, keyWithNamespace);
                String sFormat = quote ? "%s \"%s\";" : "%s %s;";
                appendLine(
                    sFormat,
                    keyWithNamespace.substring(substrFrom),
                    value
                );
            }
        }
    }

    private NetInterface getPreferredNetIf(DrbdRscData<Resource> peerRscDataRef)
    {
        NetInterface preferredNetIf = null;
        try
        {
            TreeMap<VolumeNumber, DrbdVlmData<Resource>> sortedVlmData = new TreeMap<>(
                peerRscDataRef.getVlmLayerObjects()
            );
            Entry<VolumeNumber, DrbdVlmData<Resource>> firstVolumeEntry = sortedVlmData.firstEntry();
            Resource rsc = peerRscDataRef.getAbsResource();
            Node node = rsc.getNode();

            PriorityProps prioProps = new PriorityProps();

            if (firstVolumeEntry != null)
            {
                VolumeNumber firstVlmNr = firstVolumeEntry.getKey();
                List<AbsRscLayerObject<Resource>> storageRscList = LayerUtils.getChildLayerDataByKind(
                    firstVolumeEntry.getValue().getRscLayerObject(),
                    DeviceLayerKind.STORAGE
                );
                for (AbsRscLayerObject<Resource> rscObj : storageRscList)
                {
                    VlmProviderObject<Resource> vlmProviderObject = rscObj.getVlmProviderObject(firstVlmNr);
                    if (vlmProviderObject != null)
                    {
                        prioProps.addProps(
                            vlmProviderObject.getStorPool().getProps(accCtx)
                        );
                    }
                }
            }
            prioProps.addProps(rsc.getProps(accCtx));
            prioProps.addProps(rsc.getDefinition().getProps(accCtx));
            prioProps.addProps(rsc.getDefinition().getResourceGroup().getProps(accCtx));
            prioProps.addProps(node.getProps(accCtx));
            prioProps.addProps(stltProps);

            String prefNic = prioProps.getProp(ApiConsts.KEY_STOR_POOL_PREF_NIC);

            if (prefNic != null)
            {
                preferredNetIf = node.getNetInterface(
                    accCtx,
                    new NetInterfaceName(prefNic)
                );

                if (preferredNetIf == null)
                {
                    errorReporter.logWarning(
                        String.format("Preferred network interface '%s' not found, fallback to default", prefNic)
                    );
                }
            }

            // fallback if preferred couldn't be found
            if (preferredNetIf == null)
            {
                // Try to find the 'default' network interface
                preferredNetIf = node.getNetInterface(accCtx, NetInterfaceName.DEFAULT_NET_INTERFACE_NAME);
                // If there is not even a 'default', use the first one that is found in the node's
                // list of network interfaces
                if (preferredNetIf == null)
                {
                    preferredNetIf = node.streamNetInterfaces(accCtx).findFirst().orElse(null);
                }
            }
        }
        catch (AccessDeniedException | InvalidKeyException | InvalidNameException implError)
        {
            throw new ImplementationError(implError);
        }

        return preferredNetIf;
    }

    private void appendVlmIfPresent(DrbdVlmData<Resource> vlmData, AccessContext localAccCtx, boolean isPeerRsc)
        throws AccessDeniedException
    {
        if (((Volume) vlmData.getVolume()).getFlags().isUnset(localAccCtx, Volume.Flags.DELETE))
        {
            final String disk;
            if ((!isPeerRsc && vlmData.getBackingDevice() == null) ||
                (isPeerRsc &&
                // FIXME: vlmData.getRscLayerObject().getFlags should be used here
                     vlmData.getVolume().getAbsResource().disklessForDrbdPeers(accCtx)
                ) ||
                (!isPeerRsc &&
                // FIXME: vlmData.getRscLayerObject().getFlags should be used here
                     vlmData.getVolume().getAbsResource().isDrbdDiskless(accCtx)
                )
            )
            {
                disk = "none";
            }
            else
            {
                if (!isPeerRsc)
                {
                    String backingDiskPath = vlmData.getBackingDevice();
                    if (backingDiskPath.trim().isEmpty())
                    {
                        throw new LinStorRuntimeException(
                            "Local volume does an empty block device. This might be result of an other error.",
                            "The storage driver returned an empty string instead of the path of the backing device",
                            "This is either an implementation error or just a side effect of an other " +
                                "recently occurred error. Please check the error logs and try to solve the other " +
                                "other errors first",
                            null,
                            vlmData.toString()
                        );
                    }
                    disk = backingDiskPath;
                }
                else
                {
                    // Do not use the backing disk path from the peer resource because it may be 'none' when
                    // the peer resource is converting from diskless, but the path here should not be 'none'
                    disk = "/dev/drbd/this/is/not/used";
                }
            }
            final String metaDisk;
            if (vlmData.getMetaDiskPath() == null)
            {
                metaDisk = "internal";
            }
            else
            {
                String tmpMeta = vlmData.getMetaDiskPath();
                if (tmpMeta.trim().isEmpty())
                {
                    metaDisk = "internal";
                }
                else
                {
                    metaDisk = vlmData.getMetaDiskPath();
                }
            }

            final VolumeDefinition vlmDfn = vlmData.getVolume().getVolumeDefinition();
            VolumeNumber vlmNr = vlmDfn.getVolumeNumber();
            appendLine("volume %s", vlmNr.value);
            try (Section volumeSection = new Section())
            {
                appendLine("disk        %s;", disk);

                ResourceDefinition rscDfn = vlmDfn.getResourceDefinition();
                ResourceGroup rscGrp = rscDfn.getResourceGroup();
                PriorityProps vlmPrioProps = new PriorityProps()
                    .addProps(vlmData.getVolume().getAbsResource().getProps(accCtx), "R (" + rscDfn.getName() + ")")
                    .addProps(
                        vlmDfn.getProps(accCtx),
                        "VD (" + rscDfn.getName() + "/" + vlmNr.value + ")"
                    )
                    .addProps(
                        rscGrp.getVolumeGroupProps(accCtx, vlmNr),
                        "VG (" + rscGrp.getName() + "/" + vlmNr.value + ")"
                    )
                    .addProps(rscDfn.getProps(accCtx), "RD (" + rscDfn.getName() + ")")
                    .addProps(rscGrp.getProps(accCtx), "RG (" + rscGrp.getName() + ")")
                    .addProps(
                        vlmData.getVolume().getAbsResource().getNode().getProps(accCtx),
                        "N (" + rscDfn.getName() + ")"
                    )
                    .addProps(stltProps, "C");
                if (vlmPrioProps.anyPropsHasNamespace(ApiConsts.NAMESPC_DRBD_DISK_OPTIONS))
                {
                    appendLine("disk");
                    try (Section ignore = new Section())
                    {
                        appendConflictingDrbdOptions(
                            LinStorObject.CONTROLLER,
                            ApiConsts.NAMESPC_DRBD_DISK_OPTIONS,
                            vlmPrioProps
                        );
                    }
                }

                appendLine("meta-disk   %s;", metaDisk);
                appendLine("device      minor %d;",
                    vlmData.getVlmDfnLayerObject().getMinorNr().value
                // TODO: impl and ask storPool for device
                );
                // TODO: add "disk { ... }" section
            }
        }
    }

    private void appendIndent()
    {
        appendIndent(stringBuilder);
    }

    private void appendIndent(StringBuilder sbRef)
    {
        for (int idx = 0; idx < indentDepth; idx++)
        {
            sbRef.append("    ");
        }
    }

    private void append(String format, Object... args)
    {
        stringBuilder.append(String.format(format, args));
    }

    private void appendLine(String format, Object... args)
    {
        appendIndent();
        append(format, args);
        stringBuilder.append("\n");
    }

    private static class ResourceNameComparator implements Comparator<DrbdRscData<?>>
    {
        @Override
        public int compare(DrbdRscData<?> o1, DrbdRscData<?> o2)
        {
            return o1.getAbsResource().getNode().getName().compareTo(
                   o2.getAbsResource().getNode().getName()
            );
        }
    }

    /**
     * Allows a section to be expressed using try-with-resources so that it is automatically closed.
     * <p>
     * Non-static to allow access to the indentDepth.
     */
    private class Section implements AutoCloseable
    {
        Section()
        {
            appendLine("{");
            indentDepth++;
        }

        @Override
        public void close()
        {
            indentDepth--;
            appendLine("}");
        }
    }
}
