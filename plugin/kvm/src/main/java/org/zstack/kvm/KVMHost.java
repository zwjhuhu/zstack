package org.zstack.kvm;

import org.apache.commons.lang.StringUtils;
import org.apache.logging.log4j.util.Strings;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClientException;
import org.springframework.web.util.UriComponentsBuilder;
import org.zstack.compute.host.HostBase;
import org.zstack.compute.host.HostGlobalConfig;
import org.zstack.compute.host.HostSystemTags;
import org.zstack.compute.host.MigrateNetworkExtensionPoint;
import org.zstack.compute.vm.IsoOperator;
import org.zstack.compute.vm.VmGlobalConfig;
import org.zstack.compute.vm.VmSystemTags;
import org.zstack.core.CoreGlobalProperty;
import org.zstack.core.MessageCommandRecorder;
import org.zstack.core.Platform;
import org.zstack.core.ansible.*;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.CloudBusGlobalProperty;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.resourceconfig.ResourceConfigFacade;
import org.zstack.core.db.Q;
import org.zstack.core.db.SimpleQuery;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.thread.CancelablePeriodicTask;
import org.zstack.core.thread.ChainTask;
import org.zstack.core.thread.SyncTaskChain;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.core.timeout.ApiTimeoutManager;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.Constants;
import org.zstack.header.core.*;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.errorcode.SysErrors;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.*;
import org.zstack.header.host.MigrateVmOnHypervisorMsg.StorageMigrationPolicy;
import org.zstack.header.image.ImageBootMode;
import org.zstack.header.image.ImagePlatform;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.message.NeedReplyMessage;
import org.zstack.header.network.l2.*;
import org.zstack.header.rest.JsonAsyncRESTCallback;
import org.zstack.header.rest.RESTFacade;
import org.zstack.header.storage.primary.*;
import org.zstack.header.storage.snapshot.VolumeSnapshotInventory;
import org.zstack.header.tag.SystemTagInventory;
import org.zstack.header.vm.*;
import org.zstack.header.volume.*;
import org.zstack.kvm.KVMAgentCommands.*;
import org.zstack.kvm.KVMConstant.KvmVmState;
import org.zstack.network.l3.NetworkGlobalProperty;
import org.zstack.tag.SystemTag;
import org.zstack.tag.SystemTagCreator;
import org.zstack.tag.TagManager;
import org.zstack.utils.*;
import org.zstack.utils.gson.JSONObjectUtil;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.NetworkUtils;
import org.zstack.utils.path.PathUtil;
import org.zstack.utils.ssh.Ssh;
import org.zstack.utils.ssh.SshResult;
import org.zstack.utils.ssh.SshShell;
import org.zstack.utils.tester.ZTester;

import javax.persistence.TypedQuery;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static org.zstack.core.Platform.*;
import static org.zstack.utils.CollectionDSL.e;
import static org.zstack.utils.CollectionDSL.map;

public class KVMHost extends HostBase implements Host {
    private static final CLogger logger = Utils.getLogger(KVMHost.class);
    private static final ZTester tester = Utils.getTester();

    @Autowired
    @Qualifier("KVMHostFactory")
    private KVMHostFactory factory;
    @Autowired
    private RESTFacade restf;
    @Autowired
    private KVMExtensionEmitter extEmitter;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private TagManager tagmgr;
    @Autowired
    private ApiTimeoutManager timeoutManager;
    @Autowired
    private PluginRegistry pluginRegistry;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private AnsibleFacade asf;
    @Autowired
    private ResourceConfigFacade rcf;

    private KVMHostContext context;

    // ///////////////////// REST URL //////////////////////////
    private String baseUrl;
    private String connectPath;
    private String pingPath;
    private String checkPhysicalNetworkInterfacePath;
    private String startVmPath;
    private String stopVmPath;
    private String pauseVmPath;
    private String resumeVmPath;
    private String rebootVmPath;
    private String destroyVmPath;
    private String attachDataVolumePath;
    private String detachDataVolumePath;
    private String echoPath;
    private String attachNicPath;
    private String detachNicPath;
    private String migrateVmPath;
    private String snapshotPath;
    private String mergeSnapshotPath;
    private String hostFactPath;
    private String attachIsoPath;
    private String detachIsoPath;
    private String updateNicPath;
    private String checkVmStatePath;
    private String getConsolePortPath;
    private String onlineIncreaseCpuPath;
    private String onlineIncreaseMemPath;
    private String deleteConsoleFirewall;
    private String updateHostOSPath;
    private String updateDependencyPath;

    private String agentPackageName = KVMGlobalProperty.AGENT_PACKAGE_NAME;

    KVMHost(KVMHostVO self, KVMHostContext context) {
        super(self);

        this.context = context;
        baseUrl = context.getBaseUrl();

        UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_CONNECT_PATH);
        connectPath = ub.build().toUriString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_PING_PATH);
        pingPath = ub.build().toUriString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_CHECK_PHYSICAL_NETWORK_INTERFACE_PATH);
        checkPhysicalNetworkInterfacePath = ub.build().toUriString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_START_VM_PATH);
        startVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_STOP_VM_PATH);
        stopVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_PAUSE_VM_PATH);
        pauseVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_RESUME_VM_PATH);
        resumeVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_REBOOT_VM_PATH);
        rebootVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_DESTROY_VM_PATH);
        destroyVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_ATTACH_VOLUME);
        attachDataVolumePath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_DETACH_VOLUME);
        detachDataVolumePath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_ECHO_PATH);
        echoPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_ATTACH_NIC_PATH);
        attachNicPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_DETACH_NIC_PATH);
        detachNicPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_MIGRATE_VM_PATH);
        migrateVmPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_TAKE_VOLUME_SNAPSHOT_PATH);
        snapshotPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_MERGE_SNAPSHOT_PATH);
        mergeSnapshotPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_HOST_FACT_PATH);
        hostFactPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_ATTACH_ISO_PATH);
        attachIsoPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_DETACH_ISO_PATH);
        detachIsoPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_UPDATE_NIC_PATH);
        updateNicPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_VM_CHECK_STATE);
        checkVmStatePath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_GET_VNC_PORT_PATH);
        getConsolePortPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_VM_ONLINE_INCREASE_CPU);
        onlineIncreaseCpuPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_VM_ONLINE_INCREASE_MEMORY);
        onlineIncreaseMemPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_DELETE_CONSOLE_FIREWALL_PATH);
        deleteConsoleFirewall = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_UPDATE_HOST_OS_PATH);
        updateHostOSPath = ub.build().toString();

        ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
        ub.path(KVMConstant.KVM_HOST_UPDATE_DEPENDENCY_PATH);
        updateDependencyPath = ub.build().toString();
    }

    class Http<T> {
        String path;
        AgentCommand cmd;
        Class<T> responseClass;
        String commandStr;

        public Http(String path, String cmd, Class<T> rspClz) {
            this.path = path;
            this.commandStr = cmd;
            this.responseClass = rspClz;
        }

        public Http(String path, AgentCommand cmd, Class<T> rspClz) {
            this.path = path;
            this.cmd = cmd;
            this.responseClass = rspClz;
        }

        void call(ReturnValueCompletion<T> completion) {
            call(null, completion);
        }

        void call(String resourceUuid, ReturnValueCompletion<T> completion)  {
            Map<String, String> header = new HashMap<>();
            header.put(Constants.AGENT_HTTP_HEADER_RESOURCE_UUID, resourceUuid == null ? self.getUuid() : resourceUuid);
            runBeforeAsyncJsonPostExts(header);
            if (commandStr != null) {
                restf.asyncJsonPost(path, commandStr, header, new JsonAsyncRESTCallback<T>(completion) {
                    @Override
                    public void fail(ErrorCode err) {
                        completion.fail(err);
                    }

                    @Override
                    public void success(T ret) {
                        completion.success(ret);
                    }

                    @Override
                    public Class<T> getReturnClass() {
                        return responseClass;
                    }
                }, TimeUnit.MILLISECONDS, timeoutManager.getTimeout());
            } else {
                restf.asyncJsonPost(path, cmd, header, new JsonAsyncRESTCallback<T>(completion) {
                    @Override
                    public void fail(ErrorCode err) {
                        completion.fail(err);
                    }

                    @Override
                    public void success(T ret) {
                        completion.success(ret);
                    }

                    @Override
                    public Class<T> getReturnClass() {
                        return responseClass;
                    }
                }); // DO NOT pass unit, timeout here, they are null
            }
        }

        void runBeforeAsyncJsonPostExts(Map<String, String> header) {
            if (commandStr == null) {
                commandStr = JSONObjectUtil.toJsonString(cmd);
            }

            if (commandStr == null || commandStr.isEmpty()) {
                logger.warn(String.format("commandStr is empty, path: %s, header: %s", path, header));
                return;
            }

            LinkedHashMap commandMap = JSONObjectUtil.toObject(commandStr, LinkedHashMap.class);
            LinkedHashMap kvmHostAddon = new LinkedHashMap();
            for (KVMBeforeAsyncJsonPostExtensionPoint extp : pluginRegistry.getExtensionList(KVMBeforeAsyncJsonPostExtensionPoint.class)) {
                LinkedHashMap tmpHashMap = extp.kvmBeforeAsyncJsonPostExtensionPoint(path, commandMap, header);

                if (tmpHashMap != null && !tmpHashMap.isEmpty()) {
                    tmpHashMap.keySet().stream().forEachOrdered((key -> {
                        kvmHostAddon.put(key, tmpHashMap.get(key));
                    }));
                }
            }

            if (commandStr.equals("{}")) {
                commandStr = commandStr.replaceAll("\\}$",
                        String.format("\"%s\":%s}", KVMConstant.KVM_HOST_ADDONS, JSONObjectUtil.toJsonString(kvmHostAddon)));
            } else {
                commandStr = commandStr.replaceAll("\\}$",
                        String.format(",\"%s\":%s}", KVMConstant.KVM_HOST_ADDONS, JSONObjectUtil.toJsonString(kvmHostAddon)));
            }
        }
    }

    @Override
    protected void handleApiMessage(APIMessage msg) {
        super.handleApiMessage(msg);
    }

    @Override
    protected void handleLocalMessage(Message msg) {
        if (msg instanceof CheckNetworkPhysicalInterfaceMsg) {
            handle((CheckNetworkPhysicalInterfaceMsg) msg);
        } else if (msg instanceof StartVmOnHypervisorMsg) {
            handle((StartVmOnHypervisorMsg) msg);
        } else if (msg instanceof CreateVmOnHypervisorMsg) {
            handle((CreateVmOnHypervisorMsg) msg);
        } else if (msg instanceof StopVmOnHypervisorMsg) {
            handle((StopVmOnHypervisorMsg) msg);
        } else if (msg instanceof RebootVmOnHypervisorMsg) {
            handle((RebootVmOnHypervisorMsg) msg);
        } else if (msg instanceof DestroyVmOnHypervisorMsg) {
            handle((DestroyVmOnHypervisorMsg) msg);
        } else if (msg instanceof AttachVolumeToVmOnHypervisorMsg) {
            handle((AttachVolumeToVmOnHypervisorMsg) msg);
        } else if (msg instanceof DetachVolumeFromVmOnHypervisorMsg) {
            handle((DetachVolumeFromVmOnHypervisorMsg) msg);
        } else if (msg instanceof VmAttachNicOnHypervisorMsg) {
            handle((VmAttachNicOnHypervisorMsg) msg);
        } else if (msg instanceof VmUpdateNicOnHypervisorMsg) {
            handle((VmUpdateNicOnHypervisorMsg) msg);
        } else if (msg instanceof MigrateVmOnHypervisorMsg) {
            handle((MigrateVmOnHypervisorMsg) msg);
        } else if (msg instanceof TakeSnapshotOnHypervisorMsg) {
            handle((TakeSnapshotOnHypervisorMsg) msg);
        } else if (msg instanceof MergeVolumeSnapshotOnKvmMsg) {
            handle((MergeVolumeSnapshotOnKvmMsg) msg);
        } else if (msg instanceof KVMHostAsyncHttpCallMsg) {
            handle((KVMHostAsyncHttpCallMsg) msg);
        } else if (msg instanceof KVMHostSyncHttpCallMsg) {
            handle((KVMHostSyncHttpCallMsg) msg);
        } else if (msg instanceof DetachNicFromVmOnHypervisorMsg) {
            handle((DetachNicFromVmOnHypervisorMsg) msg);
        } else if (msg instanceof AttachIsoOnHypervisorMsg) {
            handle((AttachIsoOnHypervisorMsg) msg);
        } else if (msg instanceof DetachIsoOnHypervisorMsg) {
            handle((DetachIsoOnHypervisorMsg) msg);
        } else if (msg instanceof CheckVmStateOnHypervisorMsg) {
            handle((CheckVmStateOnHypervisorMsg) msg);
        } else if (msg instanceof GetVmConsoleAddressFromHostMsg) {
            handle((GetVmConsoleAddressFromHostMsg) msg);
        } else if (msg instanceof KvmRunShellMsg) {
            handle((KvmRunShellMsg) msg);
        } else if (msg instanceof VmDirectlyDestroyOnHypervisorMsg) {
            handle((VmDirectlyDestroyOnHypervisorMsg) msg);
        } else if (msg instanceof IncreaseVmCpuMsg) {
            handle((IncreaseVmCpuMsg) msg);
        } else if (msg instanceof IncreaseVmMemoryMsg) {
            handle((IncreaseVmMemoryMsg) msg);
        } else if (msg instanceof PauseVmOnHypervisorMsg) {
            handle((PauseVmOnHypervisorMsg) msg);
        } else if (msg instanceof ResumeVmOnHypervisorMsg) {
            handle((ResumeVmOnHypervisorMsg) msg);
        } else if (msg instanceof GetKVMHostDownloadCredentialMsg) {
            handle((GetKVMHostDownloadCredentialMsg) msg);
        } else {
            super.handleLocalMessage(msg);
        }
    }

    private void handle(GetKVMHostDownloadCredentialMsg msg) {
        final GetKVMHostDownloadCredentialReply reply = new GetKVMHostDownloadCredentialReply();

        String key = asf.getPrivateKey();

        String hostname = null;
        if (Strings.isNotEmpty(msg.getDataNetworkCidr())) {
            String dataNetworkAddress = getDataNetworkAddress(self.getUuid(), msg.getDataNetworkCidr());

            if (dataNetworkAddress != null) {
                hostname = dataNetworkAddress;
            }
        }

        reply.setHostname(hostname == null ? getSelf().getManagementIp() : hostname);
        reply.setUsername(getSelf().getUsername());
        reply.setSshPort(getSelf().getPort());
        reply.setSshKey(key);
        bus.reply(msg, reply);
    }

    protected static String getDataNetworkAddress(String hostUuid, String cidr) {
        final String extraIps = HostSystemTags.EXTRA_IPS.getTokenByResourceUuid(
                hostUuid, HostSystemTags.EXTRA_IPS_TOKEN);
        if (extraIps == null) {
            logger.debug(String.format("Host [uuid:%s] has no IPs in data network", hostUuid));
            return null;
        }

        final String[] ips = extraIps.split(",");
        for (String ip: ips) {
            if (NetworkUtils.isIpv4InCidr(ip, cidr)) {
                return ip;
            }
        }

        return null;
    }

    private void handle(final IncreaseVmCpuMsg msg) {
        IncreaseVmCpuReply reply = new IncreaseVmCpuReply();

        IncreaseCpuCmd cmd = new IncreaseCpuCmd();
        cmd.setVmUuid(msg.getVmInstanceUuid());
        cmd.setCpuNum(msg.getCpuNum());
        new Http<>(onlineIncreaseCpuPath, cmd, IncreaseCpuResponse.class).call(new ReturnValueCompletion<IncreaseCpuResponse>(msg) {
            @Override
            public void success(IncreaseCpuResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                } else {
                    reply.setCpuNum(ret.getCpuNum());
                }
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    private void handle(final IncreaseVmMemoryMsg msg) {
        IncreaseVmMemoryReply reply = new IncreaseVmMemoryReply();

        IncreaseMemoryCmd cmd = new IncreaseMemoryCmd();
        cmd.setVmUuid(msg.getVmInstanceUuid());
        cmd.setMemorySize(msg.getMemorySize());
        new Http<>(onlineIncreaseMemPath, cmd, IncreaseMemoryResponse.class).call(new ReturnValueCompletion<IncreaseMemoryResponse>(msg) {
            @Override
            public void success(IncreaseMemoryResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                } else {
                    reply.setMemorySize(ret.getMemorySize());
                }
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    private void directlyDestroy(final VmDirectlyDestroyOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();

        final VmDirectlyDestroyOnHypervisorReply reply = new VmDirectlyDestroyOnHypervisorReply();
        DestroyVmCmd cmd = new DestroyVmCmd();
        cmd.setUuid(msg.getVmUuid());
        new Http<>(destroyVmPath, cmd, DestroyVmResponse.class).call(new ReturnValueCompletion<DestroyVmResponse>(completion) {
            @Override
            public void success(DestroyVmResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_DESTROY_VM_ON_HYPERVISOR, ret.getError()));
                }

                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    protected class RunInKVMHostQueue {
        private String name;
        private List<AsyncBackup> asyncBackups = new ArrayList<>();

        public RunInKVMHostQueue name(String v) {
            name = v;
            return this;
        }

        public RunInKVMHostQueue asyncBackup(AsyncBackup v) {
            asyncBackups.add(v);
            return this;
        }

        public void run(Consumer<SyncTaskChain> consumer) {
            DebugUtils.Assert(name != null, "name() must be called");
            DebugUtils.Assert(!asyncBackups.isEmpty(), "asyncBackup must be called");

            AsyncBackup one = asyncBackups.get(0);
            AsyncBackup[] rest = asyncBackups.size() > 1 ?
                    asyncBackups.subList(1, asyncBackups.size()).toArray(new AsyncBackup[asyncBackups.size()-1]) :
                    new AsyncBackup[0];

            thdf.chainSubmit(new ChainTask(one, rest) {
                @Override
                public String getSyncSignature() {
                    return id;
                }

                @Override
                public void run(SyncTaskChain chain) {
                    consumer.accept(chain);
                }

                @Override
                protected int getSyncLevel() {
                    return getHostSyncLevel();
                }

                @Override
                public String getName() {
                    return name;
                }
            });
        }
    }

    protected RunInKVMHostQueue inQueue() {
        return new RunInKVMHostQueue();
    }

    private void handle(final VmDirectlyDestroyOnHypervisorMsg msg) {
        inQueue().name(String.format("directly-delete-vm-%s-msg-on-kvm-%s", msg.getVmUuid(), self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> directlyDestroy(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private SshResult runShell(String script) {
        Ssh ssh = new Ssh();
        ssh.setHostname(self.getManagementIp());
        ssh.setPort(getSelf().getPort());
        ssh.setUsername(getSelf().getUsername());
        ssh.setPassword(getSelf().getPassword());
        ssh.shell(script);
        return ssh.runAndClose();
    }

    private void handle(KvmRunShellMsg msg) {
        SshResult result = runShell(msg.getScript());

        KvmRunShellReply reply = new KvmRunShellReply();
        if (result.isSshFailure()) {
            reply.setError(operr("unable to connect to KVM[ip:%s, username:%s, sshPort:%d ] to do DNS check," +
                            " please check if username/password is wrong; %s",
                    self.getManagementIp(), getSelf().getUsername(),
                    getSelf().getPort(), result.getExitErrorMessage()));
        } else {
            reply.setStdout(result.getStdout());
            reply.setStderr(result.getStderr());
            reply.setReturnCode(result.getReturnCode());
        }

        bus.reply(msg, reply);
    }

    private void handle(final GetVmConsoleAddressFromHostMsg msg) {
        final GetVmConsoleAddressFromHostReply reply = new GetVmConsoleAddressFromHostReply();

        GetVncPortCmd cmd = new GetVncPortCmd();
        cmd.setVmUuid(msg.getVmInstanceUuid());
        new Http<>(getConsolePortPath, cmd, GetVncPortResponse.class).call(new ReturnValueCompletion<GetVncPortResponse>(msg) {
            @Override
            public void success(GetVncPortResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                } else {
                    reply.setHostIp(self.getManagementIp());
                    reply.setProtocol(ret.getProtocol());
                    reply.setPort(ret.getPort());
                }
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    private void handle(final CheckVmStateOnHypervisorMsg msg) {
        final CheckVmStateOnHypervisorReply reply = new CheckVmStateOnHypervisorReply();
        if (self.getStatus() != HostStatus.Connected) {
            reply.setError(operr("the host[uuid:%s, status:%s] is not Connected", self.getUuid(), self.getStatus()));
            bus.reply(msg, reply);
            return;
        }

        // NOTE: don't run this message in the sync task
        // there can be many such kind of messages
        // running in the sync task may cause other tasks starved
        CheckVmStateCmd cmd = new CheckVmStateCmd();
        cmd.vmUuids = msg.getVmInstanceUuids();
        cmd.hostUuid = self.getUuid();
        new Http<>(checkVmStatePath, cmd, CheckVmStateRsp.class).call(new ReturnValueCompletion<CheckVmStateRsp>(msg) {
            @Override
            public void success(CheckVmStateRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                } else {
                    Map<String, String> m = new HashMap<>();
                    for (Map.Entry<String, String> e : ret.states.entrySet()) {
                        m.put(e.getKey(), KvmVmState.valueOf(e.getValue()).toVmInstanceState().toString());
                    }
                    reply.setStates(m);
                }

                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    private void handle(final DetachIsoOnHypervisorMsg msg) {
        inQueue().asyncBackup(msg)
                .name(String.format("detach-iso-%s-on-host-%s", msg.getIsoUuid(), self.getUuid()))
                .run(chain -> detachIso(msg, new NoErrorCompletion(msg, chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void detachIso(final DetachIsoOnHypervisorMsg msg, final NoErrorCompletion completion) {
        final DetachIsoOnHypervisorReply reply = new DetachIsoOnHypervisorReply();
        DetachIsoCmd cmd = new DetachIsoCmd();
        cmd.isoUuid = msg.getIsoUuid();
        cmd.vmUuid = msg.getVmInstanceUuid();
        Integer deviceId = IsoOperator.getIsoDeviceId(msg.getVmInstanceUuid(), msg.getIsoUuid());
        assert deviceId != null;
        cmd.deviceId = deviceId;

        KVMHostInventory inv = (KVMHostInventory) getSelfInventory();
        for (KVMPreDetachIsoExtensionPoint ext : pluginRgty.getExtensionList(KVMPreDetachIsoExtensionPoint.class)) {
            ext.preDetachIsoExtensionPoint(inv, cmd);
        }

        new Http<>(detachIsoPath, cmd, DetachIsoRsp.class).call(new ReturnValueCompletion<DetachIsoRsp>(msg, completion) {
            @Override
            public void success(DetachIsoRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                }

                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final AttachIsoOnHypervisorMsg msg) {
        inQueue().asyncBackup(msg)
                .name(String.format("attach-iso-%s-on-host-%s", msg.getIsoSpec().getImageUuid(), self.getUuid()))
                .run(chain -> attachIso(msg, new NoErrorCompletion(msg, chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void attachIso(final AttachIsoOnHypervisorMsg msg, final NoErrorCompletion completion) {
        final AttachIsoOnHypervisorReply reply = new AttachIsoOnHypervisorReply();

        IsoTO iso = new IsoTO();
        iso.setImageUuid(msg.getIsoSpec().getImageUuid());
        iso.setPath(msg.getIsoSpec().getInstallPath());
        iso.setDeviceId(msg.getIsoSpec().getDeviceId());

        AttachIsoCmd cmd = new AttachIsoCmd();
        cmd.vmUuid = msg.getVmInstanceUuid();
        cmd.iso = iso;

        KVMHostInventory inv = (KVMHostInventory) getSelfInventory();
        for (KVMPreAttachIsoExtensionPoint ext : pluginRgty.getExtensionList(KVMPreAttachIsoExtensionPoint.class)) {
            ext.preAttachIsoExtensionPoint(inv, cmd);
        }

        new Http<>(attachIsoPath, cmd, AttachIsoRsp.class).call(new ReturnValueCompletion<AttachIsoRsp>(msg, completion) {
            @Override
            public void success(AttachIsoRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                }

                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                reply.setError(err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final DetachNicFromVmOnHypervisorMsg msg) {
        inQueue().name("detach-nic-on-kvm-host-" + self.getUuid())
                .asyncBackup(msg)
                .run(chain -> detachNic(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void detachNic(final DetachNicFromVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        final DetachNicFromVmOnHypervisorReply reply = new DetachNicFromVmOnHypervisorReply();
        NicTO to = completeNicInfo(msg.getNic());

        DetachNicCommand cmd = new DetachNicCommand();
        cmd.setVmUuid(msg.getVmInstanceUuid());
        cmd.setNic(to);
        new Http<>(detachNicPath, cmd, DetachNicRsp.class).call(new ReturnValueCompletion<DetachNicRsp>(msg, completion) {
            @Override
            public void success(DetachNicRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final KVMHostSyncHttpCallMsg msg) {
        inQueue().name(String.format("execute-sync-http-call-on-kvm-host-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> executeSyncHttpCall(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void executeSyncHttpCall(KVMHostSyncHttpCallMsg msg, NoErrorCompletion completion) {
        if (!msg.isNoStatusCheck()) {
            checkStatus();
        }
        String url = buildUrl(msg.getPath());
        MessageCommandRecorder.record(msg.getCommandClassName());
        Map<String, String> headers = new HashMap<>();
        headers.put(Constants.AGENT_HTTP_HEADER_RESOURCE_UUID, self.getUuid());
        LinkedHashMap rsp = restf.syncJsonPost(url, msg.getCommand(), headers, LinkedHashMap.class);
        KVMHostSyncHttpCallReply reply = new KVMHostSyncHttpCallReply();
        reply.setResponse(rsp);
        bus.reply(msg, reply);
        completion.done();
    }

    private void handle(final KVMHostAsyncHttpCallMsg msg) {
        inQueue().name(String.format("execute-async-http-call-on-kvm-host-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> executeAsyncHttpCall(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private String buildUrl(String path) {
        UriComponentsBuilder ub = UriComponentsBuilder.newInstance();
        ub.scheme(KVMGlobalProperty.AGENT_URL_SCHEME);
        ub.host(self.getManagementIp());
        ub.port(KVMGlobalProperty.AGENT_PORT);
        if (!"".equals(KVMGlobalProperty.AGENT_URL_ROOT_PATH)) {
            ub.path(KVMGlobalProperty.AGENT_URL_ROOT_PATH);
        }
        ub.path(path);
        return ub.build().toUriString();
    }

    private void executeAsyncHttpCall(final KVMHostAsyncHttpCallMsg msg, final NoErrorCompletion completion) {
        if (!msg.isNoStatusCheck()) {
            checkStatus();
        }

        String url = buildUrl(msg.getPath());
        MessageCommandRecorder.record(msg.getCommandClassName());
        new Http<>(url, msg.getCommand(), LinkedHashMap.class)
                .call(new ReturnValueCompletion<LinkedHashMap>(msg, completion) {
            @Override
            public void success(LinkedHashMap ret) {
                KVMHostAsyncHttpCallReply reply = new KVMHostAsyncHttpCallReply();
                reply.setResponse(ret);
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                KVMHostAsyncHttpCallReply reply = new KVMHostAsyncHttpCallReply();
                if (err.isError(SysErrors.HTTP_ERROR, SysErrors.IO_ERROR)) {
                    reply.setError(err(HostErrors.OPERATION_FAILURE_GC_ELIGIBLE, err, "cannot do the operation on the KVM host"));
                } else {
                    reply.setError(err);
                }

                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final MergeVolumeSnapshotOnKvmMsg msg) {
        inQueue().name(String.format("merge-volume-snapshot-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> mergeVolumeSnapshot(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void mergeVolumeSnapshot(final MergeVolumeSnapshotOnKvmMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();

        final MergeVolumeSnapshotOnKvmReply reply = new MergeVolumeSnapshotOnKvmReply();

        VolumeInventory volume = msg.getTo();

        if (volume.getVmInstanceUuid() != null) {
            SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
            q.select(VmInstanceVO_.state);
            q.add(VmInstanceVO_.uuid, Op.EQ, volume.getVmInstanceUuid());
            VmInstanceState state = q.findValue();
            if (state != VmInstanceState.Stopped && state != VmInstanceState.Running && state != VmInstanceState.Paused && state != VmInstanceState.Destroyed) {
                throw new OperationFailureException(operr("cannot do volume snapshot merge when vm[uuid:%s] is in state of %s." +
                                " The operation is only allowed when vm is Running or Stopped", volume.getUuid(), state));
            }

            if (state == VmInstanceState.Running) {
                String libvirtVersion = KVMSystemTags.LIBVIRT_VERSION.getTokenByResourceUuid(self.getUuid(), KVMSystemTags.LIBVIRT_VERSION_TOKEN);
                if (new VersionComparator(KVMConstant.MIN_LIBVIRT_LIVE_BLOCK_COMMIT_VERSION).compare(libvirtVersion) > 0) {
                    throw new OperationFailureException(operr("live volume snapshot merge needs libvirt version greater than %s," +
                                    " current libvirt version is %s. Please stop vm and redo the operation or detach the volume if it's data volume",
                            KVMConstant.MIN_LIBVIRT_LIVE_BLOCK_COMMIT_VERSION, libvirtVersion));
                }
            }
        }

        VolumeSnapshotInventory snapshot = msg.getFrom();
        MergeSnapshotCmd cmd = new MergeSnapshotCmd();
        cmd.setFullRebase(msg.isFullRebase());
        cmd.setDestPath(volume.getInstallPath());
        cmd.setSrcPath(snapshot.getPrimaryStorageInstallPath());
        cmd.setVmUuid(volume.getVmInstanceUuid());
        cmd.setVolume(VolumeTO.valueOf(volume, (KVMHostInventory) getSelfInventory()));

        extEmitter.beforeMergeSnapshot((KVMHostInventory) getSelfInventory(), msg, cmd);
        new Http<>(mergeSnapshotPath, cmd, MergeSnapshotRsp.class)
                .call(new ReturnValueCompletion<MergeSnapshotRsp>(msg, completion) {
            @Override
            public void success(MergeSnapshotRsp ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("operation error, because:%s", ret.getError()));
                    extEmitter.afterMergeSnapshotFailed((KVMHostInventory) getSelfInventory(), msg, cmd, reply.getError());
                }
                extEmitter.afterMergeSnapshot((KVMHostInventory) getSelfInventory(), msg, cmd);
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                extEmitter.afterMergeSnapshotFailed((KVMHostInventory) getSelfInventory(), msg, cmd, reply.getError());
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final TakeSnapshotOnHypervisorMsg msg) {
        inQueue().name(String.format("take-snapshot-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> {
                    takeSnapshot(msg);
                    chain.next();
                });
    }

    private void takeSnapshot(final TakeSnapshotOnHypervisorMsg msg) {
        thdf.chainSubmit(new ChainTask(msg) {
            @Override
            public String getSyncSignature() {
                return getName();
            }

            @Override
            public void run(SyncTaskChain chain) {
                doTakeSnapshot(msg, new NoErrorCompletion() {
                    @Override
                    public void done() {
                        chain.next();
                    }
                });
            }

            @Override
            protected int getSyncLevel() {
                return KVMGlobalConfig.HOST_SNAPSHOT_SYNC_LEVEL.value(Integer.class);
            }

            @Override
            public String getName() {
                return String.format("take-snapshot-on-kvm-%s", self.getUuid());
            }
        });
    }

    private void doTakeSnapshot(final TakeSnapshotOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();

        final TakeSnapshotOnHypervisorReply reply = new TakeSnapshotOnHypervisorReply();
        TakeSnapshotCmd cmd = new TakeSnapshotCmd();

        if (msg.getVmUuid() != null) {
            SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
            q.select(VmInstanceVO_.state);
            q.add(VmInstanceVO_.uuid, SimpleQuery.Op.EQ, msg.getVmUuid());
            VmInstanceState vmState = q.findValue();
            if (vmState != VmInstanceState.Running && vmState != VmInstanceState.Stopped && vmState != VmInstanceState.Paused) {
                throw new OperationFailureException(operr("vm[uuid:%s] is not Running or Stopped, current state[%s]", msg.getVmUuid(), vmState));
            }

            if (!HostSystemTags.LIVE_SNAPSHOT.hasTag(self.getUuid())) {
                if (vmState != VmInstanceState.Stopped) {
                    throw new OperationFailureException(err(SysErrors.NO_CAPABILITY_ERROR,
                            "kvm host[uuid:%s, name:%s, ip:%s] doesn't not support live snapshot. please stop vm[uuid:%s] and try again",
                                    self.getUuid(), self.getName(), self.getManagementIp(), msg.getVmUuid()
                    ));
                }
            }

            cmd.setVolumeUuid(msg.getVolume().getUuid());
            cmd.setVmUuid(msg.getVmUuid());
            cmd.setVolume(VolumeTO.valueOf(msg.getVolume(), (KVMHostInventory) getSelfInventory()));
        }

        cmd.setVolumeInstallPath(msg.getVolume().getInstallPath());
        cmd.setInstallPath(msg.getInstallPath());
        cmd.setFullSnapshot(msg.isFullSnapshot());

        FlowChain chain = FlowChainBuilder.newSimpleFlowChain();
        chain.setName(String.format("before-take-snapshot-%s-for-volume-%s", msg.getSnapshotName(), msg.getVolume().getUuid()));
        chain.then(new NoRollbackFlow() {
            @Override
            public void run(FlowTrigger trigger, Map data) {
                extEmitter.beforeTakeSnapshot((KVMHostInventory) getSelfInventory(), msg, cmd, new Completion(trigger) {
                    @Override
                    public void success() {
                        trigger.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        trigger.fail(errorCode);
                    }
                });
            }
        }).then(new NoRollbackFlow() {
            @Override
            public void run(FlowTrigger trigger, Map data) {
                new Http<>(snapshotPath, cmd, TakeSnapshotResponse.class).call(new ReturnValueCompletion<TakeSnapshotResponse>(msg, trigger) {
                    @Override
                    public void success(TakeSnapshotResponse ret) {
                        if (ret.isSuccess()) {
                            extEmitter.afterTakeSnapshot((KVMHostInventory) getSelfInventory(), msg, cmd, ret);
                            reply.setNewVolumeInstallPath(ret.getNewVolumeInstallPath());
                            reply.setSnapshotInstallPath(ret.getSnapshotInstallPath());
                            reply.setSize(ret.getSize());
                        } else {
                            ErrorCode err = operr("operation error, because:%s", ret.getError());
                            extEmitter.afterTakeSnapshotFailed((KVMHostInventory) getSelfInventory(), msg, cmd, ret, err);
                            reply.setError(err);
                        }
                        trigger.next();
                    }

                    @Override
                    public void fail(ErrorCode errorCode) {
                        extEmitter.afterTakeSnapshotFailed((KVMHostInventory) getSelfInventory(), msg, cmd, null, errorCode);
                        reply.setError(errorCode);
                        trigger.fail(errorCode);
                    }
                });
            }
        }).done(new FlowDoneHandler(completion) {
            @Override
            public void handle(Map data) {
                bus.reply(msg, reply);
                completion.done();
            }
        }).error(new FlowErrorHandler(completion) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                reply.setError(errCode);
                bus.reply(msg, reply);
                completion.done();
            }
        }).start();
    }

    private void migrateVm(final Iterator<MigrateStruct> it, final Completion completion) {
        final String dstHostMigrateIp, dstHostMnIp, dstHostUuid;
        final String vmUuid;
        final StorageMigrationPolicy storageMigrationPolicy;
        final boolean migrateFromDestination;
        final String srcHostMigrateIp, srcHostMnIp, srcHostUuid;
        synchronized (it) {
            if (!it.hasNext()) {
                completion.success();
                return;
            }

            MigrateStruct s = it.next();
            vmUuid = s.vmUuid;
            dstHostMigrateIp = s.dstHostMigrateIp;
            dstHostMnIp = s.dstHostMnIp;
            dstHostUuid = s.dstHostUuid;

            storageMigrationPolicy = s.storageMigrationPolicy;
            migrateFromDestination = s.migrateFromDestition;
            srcHostMigrateIp = s.srcHostMigrateIp;
            srcHostMnIp = s.srcHostMnIp;
            srcHostUuid = s.srcHostUuid;
        }


        SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
        q.select(VmInstanceVO_.internalId);
        q.add(VmInstanceVO_.uuid, Op.EQ, vmUuid);
        final Long vmInternalId = q.findValue();

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("migrate-vm-%s-on-kvm-host-%s", vmUuid, self.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "migrate-vm";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        MigrateVmCmd cmd = new MigrateVmCmd();
                        cmd.setDestHostIp(dstHostMigrateIp);
                        cmd.setSrcHostIp(srcHostMigrateIp);
                        cmd.setMigrateFromDestination(migrateFromDestination);
                        cmd.setStorageMigrationPolicy(storageMigrationPolicy == null ? null : storageMigrationPolicy.toString());
                        cmd.setVmUuid(vmUuid);
                        cmd.setAutoConverge(KVMGlobalConfig.MIGRATE_AUTO_CONVERGE.value(Boolean.class));
                        cmd.setUseNuma(rcf.getResourceConfigValue(VmGlobalConfig.NUMA, vmUuid, Boolean.class));
                        cmd.setTimeout(timeoutManager.getTimeout());

                        UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(migrateVmPath);
                        ub.host(migrateFromDestination ? dstHostMnIp : srcHostMnIp);
                        String migrateUrl = ub.build().toString();
                        new Http<>(migrateUrl, cmd, MigrateVmResponse.class).call(migrateFromDestination ? dstHostUuid : srcHostUuid, new ReturnValueCompletion<MigrateVmResponse>(trigger) {
                            @Override
                            public void success(MigrateVmResponse ret) {
                                if (!ret.isSuccess()) {
                                    ErrorCode err = err(HostErrors.FAILED_TO_MIGRATE_VM_ON_HYPERVISOR,
                                            "failed to migrate vm[uuid:%s] from kvm host[uuid:%s, ip:%s] to dest host[ip:%s], %s",
                                            vmUuid, self.getUuid(), self.getManagementIp(), dstHostMigrateIp, ret.getError()
                                    );

                                    trigger.fail(err);
                                } else {
                                    String info = String.format("successfully migrated vm[uuid:%s] from kvm host[uuid:%s, ip:%s] to dest host[ip:%s]",
                                            vmUuid, self.getUuid(), self.getManagementIp(), dstHostMigrateIp);
                                    logger.debug(info);

                                    trigger.next();
                                }
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "harden-vm-console-on-dst-host";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        HardenVmConsoleCmd cmd = new HardenVmConsoleCmd();
                        cmd.vmInternalId = vmInternalId;
                        cmd.vmUuid = vmUuid;
                        cmd.hostManagementIp = dstHostMnIp;

                        UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(baseUrl);
                        ub.host(dstHostMnIp);
                        ub.path(KVMConstant.KVM_HARDEN_CONSOLE_PATH);
                        String url = ub.build().toString();
                        new Http<>(url, cmd, AgentResponse.class).call(dstHostUuid, new ReturnValueCompletion<AgentResponse>(trigger) {
                            @Override
                            public void success(AgentResponse ret) {
                                if (!ret.isSuccess()) {
                                    //TODO: add GC
                                    logger.warn(String.format("failed to harden VM[uuid:%s]'s console, %s", vmUuid, ret.getError()));
                                }

                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                //TODO add GC
                                logger.warn(String.format("failed to harden VM[uuid:%s]'s console, %s", vmUuid, errorCode));
                                // continue
                                trigger.next();
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "delete-vm-console-firewall-on-source-host";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        DeleteVmConsoleFirewallCmd cmd = new DeleteVmConsoleFirewallCmd();
                        cmd.vmInternalId = vmInternalId;
                        cmd.vmUuid = vmUuid;
                        cmd.hostManagementIp = srcHostMnIp;
                        new Http<>(deleteConsoleFirewall, cmd, AgentResponse.class).call(new ReturnValueCompletion<AgentResponse>(trigger) {
                            @Override
                            public void success(AgentResponse ret) {
                                if (!ret.isSuccess()) {
                                    logger.warn(String.format("failed to delete console firewall rule for the vm[uuid:%s] on" +
                                            " the source host[uuid:%s, ip:%s], %s", vmUuid, self.getUuid(), self.getManagementIp(), ret.getError()));
                                }

                                trigger.next();
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                //TODO
                                logger.warn(String.format("failed to delete console firewall rule for the vm[uuid:%s] on" +
                                        " the source host[uuid:%s, ip:%s], %s", vmUuid, self.getUuid(), self.getManagementIp(), errorCode));
                                trigger.next();
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        String info = String.format("successfully migrated vm[uuid:%s] from kvm host[uuid:%s, ip:%s] to dest host[ip:%s]",
                                vmUuid, self.getUuid(), self.getManagementIp(), dstHostMigrateIp);
                        logger.debug(info);

                        migrateVm(it, completion);
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    private void handle(final MigrateVmOnHypervisorMsg msg) {
        inQueue().name(String.format("migrate-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> migrateVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    class MigrateStruct {
        String vmUuid;
        String dstHostMigrateIp;
        String dstHostMnIp;
        String dstHostUuid;
        StorageMigrationPolicy storageMigrationPolicy;
        boolean migrateFromDestition;
        String srcHostMigrateIp;
        String srcHostMnIp;
        String srcHostUuid;
    }

    private MigrateStruct buildMigrateStuct(final MigrateVmOnHypervisorMsg msg){
        MigrateStruct s = new MigrateStruct();
        s.vmUuid = msg.getVmInventory().getUuid();
        s.srcHostUuid = msg.getSrcHostUuid();
        s.dstHostUuid = msg.getDestHostInventory().getUuid();
        s.storageMigrationPolicy = msg.getStorageMigrationPolicy();
        s.migrateFromDestition = msg.isMigrateFromDestination();

        MigrateNetworkExtensionPoint.MigrateInfo migrateIpInfo = null;
        for (MigrateNetworkExtensionPoint ext: pluginRgty.getExtensionList(MigrateNetworkExtensionPoint.class)) {
            MigrateNetworkExtensionPoint.MigrateInfo r = ext.getMigrationAddressForVM(s.srcHostUuid, s.dstHostUuid);
            if (r == null) {
                continue;
            }

            migrateIpInfo = r;
        }

        s.dstHostMnIp = msg.getDestHostInventory().getManagementIp();
        s.dstHostMigrateIp = migrateIpInfo == null ? s.dstHostMnIp : migrateIpInfo.dstMigrationAddress;
        s.srcHostMnIp = Q.New(HostVO.class).eq(HostVO_.uuid, msg.getSrcHostUuid()).select(HostVO_.managementIp).findValue();
        s.srcHostMigrateIp = migrateIpInfo == null ? s.srcHostMnIp : migrateIpInfo.srcMigrationAddress;
        return s;
    }

    private void migrateVm(final MigrateVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();

        List<MigrateStruct> lst = new ArrayList<>();
        MigrateStruct s = buildMigrateStuct(msg);
        lst.add(s);

        final MigrateVmOnHypervisorReply reply = new MigrateVmOnHypervisorReply();
        migrateVm(lst.iterator(), new Completion(msg, completion) {
            @Override
            public void success() {
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final VmUpdateNicOnHypervisorMsg msg) {
        inQueue().name(String.format("update-nic-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> updateNic(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void updateNic(VmUpdateNicOnHypervisorMsg msg, NoErrorCompletion completion) {
        checkStateAndStatus();
        final VmUpdateNicOnHypervisorReply reply = new VmUpdateNicOnHypervisorReply();

        List<VmNicVO> nics = Q.New(VmNicVO.class).eq(VmNicVO_.vmInstanceUuid, msg.getVmInstanceUuid()).list();

        UpdateNicCmd cmd = new UpdateNicCmd();
        cmd.setVmInstanceUuid(msg.getVmInstanceUuid());
        cmd.setNics(VmNicInventory.valueOf(nics).stream().map(this::completeNicInfo).collect(Collectors.toList()));

        KVMHostInventory inv = (KVMHostInventory) getSelfInventory();
        for (KVMPreUpdateNicExtensionPoint ext : pluginRgty.getExtensionList(KVMPreUpdateNicExtensionPoint.class)) {
            ext.preUpdateNic(inv, cmd);
        }

        new Http<>(updateNicPath, cmd, AttachNicResponse.class).call(new ReturnValueCompletion<AttachNicResponse>(msg, completion) {
            @Override
            public void success(AttachNicResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("failed to update nic[vm:%s] on kvm host[uuid:%s, ip:%s]," +
                                    "because %s", msg.getVmInstanceUuid(), self.getUuid(), self.getManagementIp(), ret.getError()));
                }

                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final VmAttachNicOnHypervisorMsg msg) {
        inQueue().name(String.format("attach-nic-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> attachNic(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void attachNic(final VmAttachNicOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();

        NicTO to = completeNicInfo(msg.getNicInventory());

        final VmAttachNicOnHypervisorReply reply = new VmAttachNicOnHypervisorReply();
        AttachNicCommand cmd = new AttachNicCommand();
        cmd.setVmUuid(msg.getNicInventory().getVmInstanceUuid());
        cmd.setNic(to);

        KVMHostInventory inv = (KVMHostInventory) getSelfInventory();
        for (KvmPreAttachNicExtensionPoint ext : pluginRgty.getExtensionList(KvmPreAttachNicExtensionPoint.class)) {
            ext.preAttachNicExtensionPoint(inv, cmd);
        }

        new Http<>(attachNicPath, cmd, AttachNicResponse.class).call(new ReturnValueCompletion<AttachNicResponse>(msg, completion) {
            @Override
            public void success(AttachNicResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("failed to attach nic[uuid:%s, vm:%s] on kvm host[uuid:%s, ip:%s]," +
                                    "because %s", msg.getNicInventory().getUuid(), msg.getNicInventory().getVmInstanceUuid(),
                            self.getUuid(), self.getManagementIp(), ret.getError()));
                }

                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }


    private void handle(final DetachVolumeFromVmOnHypervisorMsg msg) {
        inQueue().name(String.format("detach-volume-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> detachVolume(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void detachVolume(final DetachVolumeFromVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();

        final VolumeInventory vol = msg.getInventory();
        final VmInstanceInventory vm = msg.getVmInventory();
        VolumeTO to = VolumeTO.valueOfWithOutExtension(vol, (KVMHostInventory) getSelfInventory(), vm.getPlatform());

        final DetachVolumeFromVmOnHypervisorReply reply = new DetachVolumeFromVmOnHypervisorReply();
        final DetachDataVolumeCmd cmd = new DetachDataVolumeCmd();
        cmd.setVolume(to);
        cmd.setVmUuid(vm.getUuid());
        extEmitter.beforeDetachVolume((KVMHostInventory) getSelfInventory(), vm, vol, cmd);

        new Http<>(detachDataVolumePath, cmd, DetachDataVolumeResponse.class).call(new ReturnValueCompletion<DetachDataVolumeResponse>(msg, completion) {
            @Override
            public void success(DetachDataVolumeResponse ret) {
                if (!ret.isSuccess()) {
                    ErrorCode err = operr("failed to detach data volume[uuid:%s, installPath:%s] from vm[uuid:%s, name:%s] on kvm host[uuid:%s, ip:%s], because %s",
                            vol.getUuid(), vol.getInstallPath(), vm.getUuid(), vm.getName(), getSelf().getUuid(), getSelf().getManagementIp(), ret.getError());
                    reply.setError(err);
                    extEmitter.detachVolumeFailed((KVMHostInventory) getSelfInventory(), vm, vol, cmd, reply.getError());
                    bus.reply(msg, reply);
                    completion.done();
                } else {
                    extEmitter.afterDetachVolume((KVMHostInventory) getSelfInventory(), vm, vol, cmd);
                    bus.reply(msg, reply);
                    completion.done();
                }
            }

            @Override
            public void fail(ErrorCode err) {
                reply.setError(err);
                extEmitter.detachVolumeFailed((KVMHostInventory) getSelfInventory(), vm, vol, cmd, reply.getError());
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final AttachVolumeToVmOnHypervisorMsg msg) {
        inQueue().name(String.format("attach-volume-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> attachVolume(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    static String computeWwnIfAbsent(String volumeUUid) {
        String wwn;
        String tag = KVMSystemTags.VOLUME_WWN.getTag(volumeUUid);
        if (tag != null) {
            wwn = KVMSystemTags.VOLUME_WWN.getTokenByTag(tag, KVMSystemTags.VOLUME_WWN_TOKEN);
        } else {
            wwn = new WwnUtils().getRandomWwn();
            SystemTagCreator creator = KVMSystemTags.VOLUME_WWN.newSystemTagCreator(volumeUUid);
            creator.inherent = true;
            creator.setTagByTokens(Collections.singletonMap(KVMSystemTags.VOLUME_WWN_TOKEN, wwn));
            creator.create();
        }

        DebugUtils.Assert(new WwnUtils().isValidWwn(wwn), String.format("Not a valid wwn[%s] for volume[uuid:%s]", wwn, volumeUUid));
        return wwn;
    }

    private String makeAndSaveVmSystemSerialNumber(String vmUuid) {
        String serialNumber;
        String tag = VmSystemTags.VM_SYSTEM_SERIAL_NUMBER.getTag(vmUuid);
        if (tag != null) {
            serialNumber = VmSystemTags.VM_SYSTEM_SERIAL_NUMBER.getTokenByTag(tag, VmSystemTags.VM_SYSTEM_SERIAL_NUMBER_TOKEN);
        } else {
            SystemTagCreator creator = VmSystemTags.VM_SYSTEM_SERIAL_NUMBER.newSystemTagCreator(vmUuid);
            creator.ignoreIfExisting = true;
            creator.inherent = true;
            creator.setTagByTokens(map(e(VmSystemTags.VM_SYSTEM_SERIAL_NUMBER_TOKEN, UUID.randomUUID().toString())));
            SystemTagInventory inv = creator.create();
            serialNumber = VmSystemTags.VM_SYSTEM_SERIAL_NUMBER.getTokenByTag(inv.getTag(), VmSystemTags.VM_SYSTEM_SERIAL_NUMBER_TOKEN);
        }

        return serialNumber;
    }

    private void attachVolume(final AttachVolumeToVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();
        KVMHostInventory host = (KVMHostInventory) getSelfInventory();

        final VolumeInventory vol = msg.getInventory();
        final VmInstanceInventory vm = msg.getVmInventory();
        VolumeTO to = VolumeTO.valueOfWithOutExtension(vol, host, vm.getPlatform());

        final AttachVolumeToVmOnHypervisorReply reply = new AttachVolumeToVmOnHypervisorReply();
        final AttachDataVolumeCmd cmd = new AttachDataVolumeCmd();
        cmd.setVolume(to);
        cmd.setVmUuid(msg.getVmInventory().getUuid());
        cmd.getAddons().put("attachedDataVolumes", VolumeTO.valueOf(msg.getAttachedDataVolumes(), host));
        Map data = new HashMap();
        extEmitter.beforeAttachVolume((KVMHostInventory) getSelfInventory(), vm, vol, cmd, data);
        new Http<>(attachDataVolumePath, cmd, AttachDataVolumeResponse.class).call(new ReturnValueCompletion<AttachDataVolumeResponse>(msg, completion) {
            @Override
            public void success(AttachDataVolumeResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(operr("failed to attach data volume[uuid:%s, installPath:%s] to vm[uuid:%s, name:%s]" +
                                    " on kvm host[uuid:%s, ip:%s], because %s", vol.getUuid(), vol.getInstallPath(), vm.getUuid(), vm.getName(),
                            getSelf().getUuid(), getSelf().getManagementIp(), ret.getError()));
                    extEmitter.attachVolumeFailed((KVMHostInventory) getSelfInventory(), vm, vol, cmd, reply.getError(), data);
                } else {
                    extEmitter.afterAttachVolume((KVMHostInventory) getSelfInventory(), vm, vol, cmd);
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                extEmitter.attachVolumeFailed((KVMHostInventory) getSelfInventory(), vm, vol, cmd, err, data);
                reply.setError(err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final DestroyVmOnHypervisorMsg msg) {
        inQueue().name(String.format("destroy-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> destroyVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void destroyVm(final DestroyVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();

        final VmInstanceInventory vminv = msg.getVmInventory();

        try {
            extEmitter.beforeDestroyVmOnKvm(KVMHostInventory.valueOf(getSelf()), vminv);
        } catch (KVMException e) {
            ErrorCode err = operr("failed to destroy vm[uuid:%s name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(), vminv.getName(),
                    self.getUuid(), self.getManagementIp(), e.getMessage());
            throw new OperationFailureException(err);
        }

        DestroyVmCmd cmd = new DestroyVmCmd();
        cmd.setUuid(vminv.getUuid());
        new Http<>(destroyVmPath, cmd, DestroyVmResponse.class).call(new ReturnValueCompletion<DestroyVmResponse>(msg, completion) {
            @Override
            public void success(DestroyVmResponse ret) {
                DestroyVmOnHypervisorReply reply = new DestroyVmOnHypervisorReply();
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_DESTROY_VM_ON_HYPERVISOR, "unable to destroy vm[uuid:%s,  name:%s] on kvm host [uuid:%s, ip:%s], because %s", vminv.getUuid(),
                            vminv.getName(), self.getUuid(), self.getManagementIp(), ret.getError()));
                    extEmitter.destroyVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, reply.getError());
                } else {
                    logger.debug(String.format("successfully destroyed vm[uuid:%s] on kvm host[uuid:%s]", vminv.getUuid(), self.getUuid()));
                    extEmitter.destroyVmOnKvmSuccess(KVMHostInventory.valueOf(getSelf()), vminv);
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                DestroyVmOnHypervisorReply reply = new DestroyVmOnHypervisorReply();

                if (err.isError(SysErrors.HTTP_ERROR, SysErrors.IO_ERROR, SysErrors.TIMEOUT)) {
                    err = err(HostErrors.OPERATION_FAILURE_GC_ELIGIBLE, err, "unable to destroy a vm");
                }

                reply.setError(err);
                extEmitter.destroyVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, reply.getError());
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final RebootVmOnHypervisorMsg msg) {
        inQueue().name(String.format("reboot-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> rebootVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private List<String> toKvmBootDev(List<String> order) {
        List<String> ret = new ArrayList<String>();
        for (String o : order) {
            if (VmBootDevice.HardDisk.toString().equals(o)) {
                ret.add(BootDev.hd.toString());
            } else if (VmBootDevice.CdRom.toString().equals(o)) {
                ret.add(BootDev.cdrom.toString());
            } else {
                throw new CloudRuntimeException(String.format("unknown boot device[%s]", o));
            }
        }

        return ret;
    }

    private void rebootVm(final RebootVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStateAndStatus();
        final VmInstanceInventory vminv = msg.getVmInventory();

        try {
            extEmitter.beforeRebootVmOnKvm(KVMHostInventory.valueOf(getSelf()), vminv);
        } catch (KVMException e) {
            String err = String.format("failed to reboot vm[uuid:%s name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(), vminv.getName(),
                    self.getUuid(), self.getManagementIp(), e.getMessage());
            logger.warn(err, e);
            throw new OperationFailureException(operr(err));
        }

        RebootVmCmd cmd = new RebootVmCmd();
        long timeout = TimeUnit.MILLISECONDS.toSeconds(msg.getTimeout());
        cmd.setUuid(vminv.getUuid());
        cmd.setTimeout(timeout);
        cmd.setBootDev(toKvmBootDev(msg.getBootOrders()));
        new Http<>(rebootVmPath, cmd, RebootVmResponse.class).call(new ReturnValueCompletion<RebootVmResponse>(msg, completion) {
            @Override
            public void success(RebootVmResponse ret) {
                RebootVmOnHypervisorReply reply = new RebootVmOnHypervisorReply();
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_REBOOT_VM_ON_HYPERVISOR, "unable to reboot vm[uuid:%s, name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(),
                            vminv.getName(), self.getUuid(), self.getManagementIp(), ret.getError()));
                    extEmitter.rebootVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, reply.getError());
                } else {
                    extEmitter.rebootVmOnKvmSuccess(KVMHostInventory.valueOf(getSelf()), vminv);
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                RebootVmOnHypervisorReply reply = new RebootVmOnHypervisorReply();
                reply.setError(err);
                extEmitter.rebootVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final StopVmOnHypervisorMsg msg) {
        inQueue().name(String.format("stop-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> stopVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void stopVm(final StopVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();
        final VmInstanceInventory vminv = msg.getVmInventory();

        try {
            extEmitter.beforeStopVmOnKvm(KVMHostInventory.valueOf(getSelf()), vminv);
        } catch (KVMException e) {
            ErrorCode err = operr("failed to stop vm[uuid:%s name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(), vminv.getName(),
                    self.getUuid(), self.getManagementIp(), e.getMessage());
            throw new OperationFailureException(err);
        }

        StopVmCmd cmd = new StopVmCmd();
        cmd.setUuid(vminv.getUuid());
        cmd.setType(msg.getType());
        cmd.setTimeout(120);
        new Http<>(stopVmPath, cmd, StopVmResponse.class).call(new ReturnValueCompletion<StopVmResponse>(msg, completion) {
            @Override
            public void success(StopVmResponse ret) {
                StopVmOnHypervisorReply reply = new StopVmOnHypervisorReply();
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_STOP_VM_ON_HYPERVISOR, "unable to stop vm[uuid:%s,  name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(),
                            vminv.getName(), self.getUuid(), self.getManagementIp(), ret.getError()));
                    logger.warn(reply.getError().getDetails());
                    extEmitter.stopVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, reply.getError());
                } else {
                    extEmitter.stopVmOnKvmSuccess(KVMHostInventory.valueOf(getSelf()), vminv);
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                StopVmOnHypervisorReply reply = new StopVmOnHypervisorReply();
                if (err.isError(SysErrors.IO_ERROR, SysErrors.HTTP_ERROR)) {
                    err = err(HostErrors.OPERATION_FAILURE_GC_ELIGIBLE, err, "unable to stop a vm");
                }

                reply.setError(err);
                extEmitter.stopVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), vminv, err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    @Transactional
    private void setDataVolumeUseVirtIOSCSI(final VmInstanceSpec spec) {
        String vmUuid = spec.getVmInventory().getUuid();
        Map<String, Integer> diskOfferingUuid_Num = new HashMap<>();
        List<Map<String, String>> tokenList = KVMSystemTags.DISK_OFFERING_VIRTIO_SCSI.getTokensOfTagsByResourceUuid(vmUuid);
        for (Map<String, String> tokens : tokenList) {
            String diskOfferingUuid = tokens.get(KVMSystemTags.DISK_OFFERING_VIRTIO_SCSI_TOKEN);
            Integer num = Integer.parseInt(tokens.get(KVMSystemTags.DISK_OFFERING_VIRTIO_SCSI_NUM_TOKEN));
            diskOfferingUuid_Num.put(diskOfferingUuid, num);
        }
        for (VolumeInventory volumeInv : spec.getDestDataVolumes()) {
            if (volumeInv.getType().equals(VolumeType.Root.toString())) {
                continue;
            }
            if (diskOfferingUuid_Num.containsKey(volumeInv.getDiskOfferingUuid())
                    && diskOfferingUuid_Num.get(volumeInv.getDiskOfferingUuid()) > 0) {
                tagmgr.createNonInherentSystemTag(volumeInv.getUuid(),
                        KVMSystemTags.VOLUME_VIRTIO_SCSI.getTagFormat(),
                        VolumeVO.class.getSimpleName());
                diskOfferingUuid_Num.put(volumeInv.getDiskOfferingUuid(),
                        diskOfferingUuid_Num.get(volumeInv.getDiskOfferingUuid()) - 1);
            }
        }

    }

    private void handle(final CreateVmOnHypervisorMsg msg) {
        inQueue().name(String.format("start-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> {
                    setDataVolumeUseVirtIOSCSI(msg.getVmSpec());
                    startVm(msg.getVmSpec(), msg, new NoErrorCompletion(chain) {
                        @Override
                        public void done() {
                            chain.next();
                        }
                    });
                });
    }

    @Transactional
    private L2NetworkInventory getL2NetworkTypeFromL3NetworkUuid(String l3NetworkUuid) {
        String sql = "select l2 from L2NetworkVO l2 where l2.uuid = (select l3.l2NetworkUuid from L3NetworkVO l3 where l3.uuid = :l3NetworkUuid)";
        TypedQuery<L2NetworkVO> query = dbf.getEntityManager().createQuery(sql, L2NetworkVO.class);
        query.setParameter("l3NetworkUuid", l3NetworkUuid);
        L2NetworkVO l2vo = query.getSingleResult();
        return L2NetworkInventory.valueOf(l2vo);
    }

    @Transactional(readOnly = true)
    private NicTO completeNicInfo(VmNicInventory nic) {
        /* all l3 networks of the nic has same l2 network */
        L2NetworkInventory l2inv = getL2NetworkTypeFromL3NetworkUuid(nic.getL3NetworkUuid());
        KVMCompleteNicInformationExtensionPoint extp = factory.getCompleteNicInfoExtension(L2NetworkType.valueOf(l2inv.getType()));
        NicTO to = extp.completeNicInformation(l2inv, nic);

        if (to.getUseVirtio() == null) {
            SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
            q.select(VmInstanceVO_.platform);
            q.add(VmInstanceVO_.uuid, Op.EQ, nic.getVmInstanceUuid());
            String platform = q.findValue();

            to.setUseVirtio(ImagePlatform.valueOf(platform).isParaVirtualization());
            to.setIps(getCleanTrafficIp(nic));
        }

        return to;
    }

    private List<String> getCleanTrafficIp(VmNicInventory nic) {
        boolean isUserVm = Q.New(VmInstanceVO.class)
                .eq(VmInstanceVO_.uuid, nic.getVmInstanceUuid()).select(VmInstanceVO_.type)
                .findValue().equals(VmInstanceConstant.USER_VM_TYPE);

        if (!isUserVm) {
            return null;
        }

        String tagValue = VmSystemTags.CLEAN_TRAFFIC.getTokenByResourceUuid(nic.getVmInstanceUuid(), VmSystemTags.CLEAN_TRAFFIC_TOKEN);
        if (Boolean.valueOf(tagValue) || (tagValue == null && VmGlobalConfig.VM_CLEAN_TRAFFIC.value(Boolean.class))) {
            return VmNicHelper.getIpAddresses(nic);
        }

        return null;
    }

    static String getVolumeTOType(VolumeInventory vol) {
        DebugUtils.Assert(vol.getInstallPath() != null, String.format("volume [%s] installPath is null, it has not been initialized", vol.getUuid()));
        return vol.getInstallPath().startsWith("iscsi") ? VolumeTO.ISCSI : VolumeTO.FILE;
    }

    private void startVm(final VmInstanceSpec spec, final NeedReplyMessage msg, final NoErrorCompletion completion) {
        checkStateAndStatus();

        final StartVmCmd cmd = new StartVmCmd();

        boolean virtio;
        String nestedVirtualization;
        String platform = spec.getVmInventory().getPlatform() == null ? spec.getImageSpec().getInventory().getPlatform() :
                spec.getVmInventory().getPlatform();

        if (ImagePlatform.Windows.toString().equals(platform)) {
            virtio = VmSystemTags.WINDOWS_VOLUME_ON_VIRTIO.hasTag(spec.getVmInventory().getUuid());
        } else {
            virtio = ImagePlatform.valueOf(platform).isParaVirtualization();
        }

        int cpuNum = spec.getVmInventory().getCpuNum();
        cmd.setCpuNum(cpuNum);

        int socket;
        int cpuOnSocket;
        //TODO: this is a HACK!!!
        if (ImagePlatform.Windows.toString().equals(platform) || ImagePlatform.WindowsVirtio.toString().equals(platform)) {
            if (cpuNum == 1) {
                socket = 1;
                cpuOnSocket = 1;
            } else if (cpuNum % 2 == 0) {
                socket = 2;
                cpuOnSocket = cpuNum / 2;
            } else {
                socket = cpuNum;
                cpuOnSocket = 1;
            }
        } else {
            socket = 1;
            cpuOnSocket = cpuNum;
        }

        cmd.setSocketNum(socket);
        cmd.setCpuOnSocket(cpuOnSocket);
        cmd.setVmName(spec.getVmInventory().getName());
        cmd.setVmInstanceUuid(spec.getVmInventory().getUuid());
        cmd.setCpuSpeed(spec.getVmInventory().getCpuSpeed());
        cmd.setMemory(spec.getVmInventory().getMemorySize());
        cmd.setMaxMemory(self.getCapacity().getTotalPhysicalMemory());
        cmd.setClock(ImagePlatform.isType(platform, ImagePlatform.Windows, ImagePlatform.WindowsVirtio) ? "localtime" : "utc");
        cmd.setVideoType(VmGlobalConfig.VM_VIDEO_TYPE.value(String.class));
        cmd.setInstanceOfferingOnlineChange(VmSystemTags.INSTANCEOFFERING_ONLIECHANGE.getTokenByResourceUuid(spec.getVmInventory().getUuid(), VmSystemTags.INSTANCEOFFERING_ONLINECHANGE_TOKEN) != null);
        cmd.setKvmHiddenState(rcf.getResourceConfigValue(VmGlobalConfig.KVM_HIDDEN_STATE, spec.getVmInventory().getClusterUuid(), Boolean.class));
        cmd.setSpiceStreamingMode(VmGlobalConfig.VM_SPICE_STREAMING_MODE.value(String.class));
        cmd.setEmulateHyperV(rcf.getResourceConfigValue(VmGlobalConfig.EMULATE_HYPERV, spec.getVmInventory().getUuid(), Boolean.class));
        cmd.setAdditionalQmp(VmGlobalConfig.ADDITIONAL_QMP.value(Boolean.class));
        cmd.setApplianceVm(spec.getVmInventory().getType().equals("ApplianceVm"));
        cmd.setSystemSerialNumber(makeAndSaveVmSystemSerialNumber(spec.getVmInventory().getUuid()));

        String machineType = VmSystemTags.MACHINE_TYPE.getTokenByResourceUuid(cmd.getVmInstanceUuid(),
                VmInstanceVO.class, VmSystemTags.MACHINE_TYPE_TOKEN);
        cmd.setMachineType(StringUtils.isNotEmpty(machineType) ? machineType : "pc");
        if (VmMachineType.q35.toString().equals(machineType)) {
            cmd.setPciePortNums(VmGlobalConfig.PCIE_PORT_NUMS.value(Integer.class));
        }

        VolumeTO rootVolume = new VolumeTO();
        rootVolume.setInstallPath(spec.getDestRootVolume().getInstallPath());
        rootVolume.setDeviceId(spec.getDestRootVolume().getDeviceId());
        rootVolume.setDeviceType(getVolumeTOType(spec.getDestRootVolume()));
        rootVolume.setVolumeUuid(spec.getDestRootVolume().getUuid());
        rootVolume.setUseVirtio(virtio);
        rootVolume.setUseVirtioSCSI(KVMSystemTags.VOLUME_VIRTIO_SCSI.hasTag(spec.getDestRootVolume().getUuid()));
        rootVolume.setWwn(computeWwnIfAbsent(spec.getDestRootVolume().getUuid()));
        rootVolume.setCacheMode(KVMGlobalConfig.LIBVIRT_CACHE_MODE.value());

        nestedVirtualization = KVMGlobalConfig.NESTED_VIRTUALIZATION.value(String.class);
        cmd.setNestedVirtualization(nestedVirtualization);
        cmd.setRootVolume(rootVolume);
        cmd.setUseBootMenu(VmGlobalConfig.VM_BOOT_MENU.value(Boolean.class));

        List<VolumeTO> dataVolumes = new ArrayList<>(spec.getDestDataVolumes().size());
        for (VolumeInventory data : spec.getDestDataVolumes()) {
            VolumeTO v = VolumeTO.valueOfWithOutExtension(data, (KVMHostInventory) getSelfInventory(), spec.getVmInventory().getPlatform());
            // always use virtio driver for data volume
            // set bug https://github.com/zxwing/premium/issues/1050
            v.setUseVirtio(true);
            dataVolumes.add(v);
        }
        dataVolumes.sort(Comparator.comparing(VolumeTO::getDeviceId));
        cmd.setDataVolumes(dataVolumes);
        cmd.setVmInternalId(spec.getVmInventory().getInternalId());

        List<NicTO> nics = new ArrayList<>(spec.getDestNics().size());
        for (VmNicInventory nic : spec.getDestNics()) {
            NicTO to = completeNicInfo(nic);
            nics.add(to);
        }
        nics = nics.stream().sorted(Comparator.comparing(NicTO::getDeviceId)).collect(Collectors.toList());
        cmd.setNics(nics);

        for (VmInstanceSpec.CdRomSpec cdRomSpec : spec.getCdRomSpecs()) {
            CdRomTO cdRomTO = new CdRomTO();
            cdRomTO.setPath(cdRomSpec.getInstallPath());
            cdRomTO.setImageUuid(cdRomSpec.getImageUuid());
            cdRomTO.setDeviceId(cdRomSpec.getDeviceId());
            cdRomTO.setEmpty(cdRomSpec.getImageUuid() == null);
            cmd.getCdRoms().add(cdRomTO);
        }

        String bootMode = VmSystemTags.BOOT_MODE.getTokenByResourceUuid(spec.getVmInventory().getUuid(), VmSystemTags.BOOT_MODE_TOKEN);
        cmd.setBootMode(bootMode == null ? ImageBootMode.Legacy.toString() : bootMode);
        cmd.setBootDev(toKvmBootDev(spec.getBootOrders()));
        cmd.setHostManagementIp(self.getManagementIp());
        cmd.setConsolePassword(spec.getConsolePassword());
        cmd.setUsbRedirect(spec.getUsbRedirect());
        cmd.setVDIMonitorNumber(Integer.valueOf(spec.getVDIMonitorNumber()));
        cmd.setUseNuma(rcf.getResourceConfigValue(VmGlobalConfig.NUMA, spec.getVmInventory().getUuid(), Boolean.class));
        cmd.setVmPortOff(VmGlobalConfig.VM_PORT_OFF.value(Boolean.class));
        cmd.setConsoleMode("vnc");
        cmd.setTimeout(TimeUnit.MINUTES.toSeconds(5));
        if (spec.isCreatePaused()) {
            cmd.setCreatePaused(true);
        }

        addons(spec, cmd);
        KVMHostInventory khinv = KVMHostInventory.valueOf(getSelf());
        extEmitter.beforeStartVmOnKvm(khinv, spec, cmd);

        extEmitter.addOn(khinv, spec, cmd);

        new Http<>(startVmPath, cmd, StartVmResponse.class).call(new ReturnValueCompletion<StartVmResponse>(msg, completion) {
            @Override
            public void success(StartVmResponse ret) {
                StartVmOnHypervisorReply reply = new StartVmOnHypervisorReply();
                if (ret.isSuccess()) {
                    String info = String.format("successfully start vm[uuid:%s name:%s] on kvm host[uuid:%s, ip:%s]",
                            spec.getVmInventory().getUuid(), spec.getVmInventory().getName(),
                            self.getUuid(), self.getManagementIp());
                    logger.debug(info);
                    extEmitter.startVmOnKvmSuccess(KVMHostInventory.valueOf(getSelf()), spec);
                } else {
                    reply.setError(err(HostErrors.FAILED_TO_START_VM_ON_HYPERVISOR, "failed to start vm[uuid:%s name:%s] on kvm host[uuid:%s, ip:%s], because %s",
                            spec.getVmInventory().getUuid(), spec.getVmInventory().getName(),
                            self.getUuid(), self.getManagementIp(), ret.getError()));
                    logger.warn(reply.getError().getDetails());
                    extEmitter.startVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), spec, reply.getError());
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                StartVmOnHypervisorReply reply = new StartVmOnHypervisorReply();
                reply.setError(err);
                reply.setSuccess(false);
                extEmitter.startVmOnKvmFailed(KVMHostInventory.valueOf(getSelf()), spec, err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void addons(final VmInstanceSpec spec, StartVmCmd cmd) {
        KVMAddons.Channel chan = new KVMAddons.Channel();
        chan.setSocketPath(makeChannelSocketPath(spec.getVmInventory().getUuid()));
        chan.setTargetName("org.qemu.guest_agent.0");
        cmd.getAddons().put(KVMAddons.Channel.NAME, chan);
        logger.debug(String.format("make kvm channel device[path:%s, target:%s]", chan.getSocketPath(), chan.getTargetName()));

    }

    private String makeChannelSocketPath(String apvmuuid) {
        return PathUtil.join(String.format("/var/lib/libvirt/qemu/%s", apvmuuid));
    }

    private void handle(final StartVmOnHypervisorMsg msg) {
        inQueue().name(String.format("start-vm-on-kvm-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> startVmInQueue(msg, chain));
    }

    private void startVmInQueue(StartVmOnHypervisorMsg msg, SyncTaskChain outterChain) {
        thdf.chainSubmit(new ChainTask(msg, outterChain) {
            @Override
            public String getSyncSignature() {
                return getName();
            }

            @Override
            public void run(final SyncTaskChain chain) {
                startVm(msg.getVmSpec(), msg, new NoErrorCompletion(chain, outterChain) {
                    @Override
                    public void done() {
                        chain.next();
                        outterChain.next();
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("start-vm-on-kvm-%s-inner-queue", self.getUuid());
            }

            @Override
            protected int getSyncLevel() {
                return KVMGlobalConfig.VM_CREATE_CONCURRENCY.value(Integer.class);
            }
        });
    }

    private void handle(final CheckNetworkPhysicalInterfaceMsg msg) {
        inQueue().name(String.format("check-network-physical-interface-on-host-%s", self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> checkPhysicalInterface(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void pauseVm(final PauseVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();
        final VmInstanceInventory vminv = msg.getVmInventory();
        PauseVmOnHypervisorReply reply = new PauseVmOnHypervisorReply();
        PauseVmCmd cmd = new PauseVmCmd();
        cmd.setUuid(vminv.getUuid());
        cmd.setTimeout(120);
        new Http<>(pauseVmPath, cmd, PauseVmResponse.class).call(new ReturnValueCompletion<PauseVmResponse>(msg, completion) {
            @Override
            public void success(PauseVmResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_STOP_VM_ON_HYPERVISOR, "unable to pause vm[uuid:%s,  name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(),
                            vminv.getName(), self.getUuid(), self.getManagementIp(), ret.getError()));
                    logger.warn(reply.getError().getDetails());
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                reply.setError(err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void handle(final PauseVmOnHypervisorMsg msg) {
        inQueue().name(String.format("pause-vm-%s-on-host-%s", msg.getVmInventory().getUuid(), self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> pauseVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void handle(final ResumeVmOnHypervisorMsg msg) {
        inQueue().name(String.format("resume-vm-%s-on-host-%s", msg.getVmInventory().getUuid(), self.getUuid()))
                .asyncBackup(msg)
                .run(chain -> resumeVm(msg, new NoErrorCompletion(chain) {
                    @Override
                    public void done() {
                        chain.next();
                    }
                }));
    }

    private void resumeVm(final ResumeVmOnHypervisorMsg msg, final NoErrorCompletion completion) {
        checkStatus();
        final VmInstanceInventory vminv = msg.getVmInventory();
        ResumeVmOnHypervisorReply reply = new ResumeVmOnHypervisorReply();
        ResumeVmCmd cmd = new ResumeVmCmd();
        cmd.setUuid(vminv.getUuid());
        cmd.setTimeout(120);

        new Http<>(resumeVmPath, cmd, ResumeVmResponse.class).call(new ReturnValueCompletion<ResumeVmResponse>(msg, completion) {
            @Override
            public void success(ResumeVmResponse ret) {
                if (!ret.isSuccess()) {
                    reply.setError(err(HostErrors.FAILED_TO_STOP_VM_ON_HYPERVISOR, "unable to resume vm[uuid:%s,  name:%s] on kvm host[uuid:%s, ip:%s], because %s", vminv.getUuid(),
                            vminv.getName(), self.getUuid(), self.getManagementIp(), ret.getError()));
                    logger.warn(reply.getError().getDetails());
                }
                bus.reply(msg, reply);
                completion.done();
            }

            @Override
            public void fail(ErrorCode err) {
                reply.setError(err);
                bus.reply(msg, reply);
                completion.done();
            }
        });
    }

    private void checkPhysicalInterface(CheckNetworkPhysicalInterfaceMsg msg, NoErrorCompletion completion) {
        checkState();
        CheckPhysicalNetworkInterfaceCmd cmd = new CheckPhysicalNetworkInterfaceCmd();
        cmd.addInterfaceName(msg.getPhysicalInterface());
        CheckNetworkPhysicalInterfaceReply reply = new CheckNetworkPhysicalInterfaceReply();
        CheckPhysicalNetworkInterfaceResponse rsp = restf.syncJsonPost(checkPhysicalNetworkInterfacePath, cmd, CheckPhysicalNetworkInterfaceResponse.class);
        if (!rsp.isSuccess()) {
            if (rsp.getFailedInterfaceNames().isEmpty()) {
                reply.setError(operr("operation error, because:%s", rsp.getError()));
            } else {
                reply.setError(operr("failed to check physical network interfaces[names : %s] on kvm host[uuid:%s, ip:%s]",
                        msg.getPhysicalInterface(), context.getInventory().getUuid(), context.getInventory().getManagementIp()));
            }
        }
        bus.reply(msg, reply);
        completion.done();
    }

    @Override
    public void handleMessage(Message msg) {
        try {
            if (msg instanceof APIMessage) {
                handleApiMessage((APIMessage) msg);
            } else {
                handleLocalMessage(msg);
            }
        } catch (Exception e) {
            bus.logExceptionWithMessageDump(msg, e);
            bus.replyErrorByMessageType(msg, e);
        }
    }

    @Override
    public void changeStateHook(HostState current, HostStateEvent stateEvent, HostState next) {
    }

    @Override
    public void deleteHook() {
    }

    @Override
    protected HostInventory getSelfInventory() {
        return KVMHostInventory.valueOf(getSelf());
    }

    @Override
    protected void pingHook(final Completion completion) {
        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("ping-kvm-host-%s", self.getUuid()));
        chain.then(new ShareFlow() {
            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "ping-host";

                    @AfterDone
                    List<Runnable> afterDone = new ArrayList<>();

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        PingCmd cmd = new PingCmd();
                        cmd.hostUuid = self.getUuid();
                        restf.asyncJsonPost(pingPath, cmd, new JsonAsyncRESTCallback<PingResponse>(trigger) {
                            @Override
                            public void fail(ErrorCode err) {
                                trigger.fail(err);
                            }

                            @Override
                            public void success(PingResponse ret) {
                                if (ret.isSuccess()) {
                                    if (!self.getUuid().equals(ret.getHostUuid())) {
                                        afterDone.add(() -> {
                                            String info = i18n("detected abnormal status[host uuid change, expected: %s but: %s] of kvmagent," +
                                                    "it's mainly caused by kvmagent restarts behind zstack management server. Report this to ping task, it will issue a reconnect soon", self.getUuid(), ret.getHostUuid());
                                            logger.warn(info);

                                            changeConnectionState(HostStatusEvent.disconnected);
                                            new HostDisconnectedCanonicalEvent(self.getUuid(), argerr(info)).fire();

                                            ReconnectHostMsg rmsg = new ReconnectHostMsg();
                                            rmsg.setHostUuid(self.getUuid());
                                            bus.makeTargetServiceIdByResourceUuid(rmsg, HostConstant.SERVICE_ID, self.getUuid());
                                            bus.send(rmsg);
                                        });
                                    }

                                    trigger.next();
                                } else {
                                     trigger.fail(operr("%s", ret.getError()));
                                }
                            }

                            @Override
                            public Class<PingResponse> getReturnClass() {
                                return PingResponse.class;
                            }
                        },TimeUnit.SECONDS, HostGlobalConfig.PING_HOST_TIMEOUT.value(Long.class));
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "call-ping-no-failure-plugins";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        List<KVMPingAgentNoFailureExtensionPoint> exts = pluginRgty.getExtensionList(KVMPingAgentNoFailureExtensionPoint.class);
                        if (exts.isEmpty()) {
                            trigger.next();
                            return;
                        }

                        AsyncLatch latch = new AsyncLatch(exts.size(), new NoErrorCompletion(trigger) {
                            @Override
                            public void done() {
                                trigger.next();
                            }
                        });

                        KVMHostInventory inv = (KVMHostInventory) getSelfInventory();
                        for (KVMPingAgentNoFailureExtensionPoint ext : exts) {
                            ext.kvmPingAgentNoFailure(inv, new NoErrorCompletion(latch) {
                                @Override
                                public void done() {
                                    latch.ack();
                                }
                            });
                        }
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "call-ping-plugins";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        List<KVMPingAgentExtensionPoint> exts = pluginRgty.getExtensionList(KVMPingAgentExtensionPoint.class);
                        Iterator<KVMPingAgentExtensionPoint> it = exts.iterator();
                        callPlugin(it, trigger);
                    }

                    private void callPlugin(Iterator<KVMPingAgentExtensionPoint> it, FlowTrigger trigger) {
                        if (!it.hasNext()) {
                            trigger.next();
                            return;
                        }

                        KVMPingAgentExtensionPoint ext = it.next();
                        logger.debug(String.format("calling KVMPingAgentExtensionPoint[%s]", ext.getClass()));
                        ext.kvmPingAgent((KVMHostInventory) getSelfInventory(), new Completion(trigger) {
                            @Override
                            public void success() {
                                callPlugin(it, trigger);
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        completion.success();
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    @Override
    protected int getVmMigrateQuantity() {
        return KVMGlobalConfig.VM_MIGRATION_QUANTITY.value(Integer.class);
    }

    private ErrorCode connectToAgent() {
        ErrorCode errCode = null;
        try {
            ConnectCmd cmd = new ConnectCmd();
            cmd.setHostUuid(self.getUuid());
            cmd.setSendCommandUrl(restf.getSendCommandUrl());
            cmd.setIptablesRules(KVMGlobalProperty.IPTABLES_RULES);
            cmd.setIgnoreMsrs(KVMGlobalConfig.KVM_IGNORE_MSRS.value(Boolean.class));
            cmd.setPageTableExtensionDisabled(HostSystemTags.PAGE_TABLE_EXTENSION_DISABLED.hasTag(self.getUuid(), HostVO.class));
            ConnectResponse rsp = restf.syncJsonPost(connectPath, cmd, ConnectResponse.class);
            if (!rsp.isSuccess() || !rsp.isIptablesSucc()) {
                errCode = operr("unable to connect to kvm host[uuid:%s, ip:%s, url:%s], because %s",
                        self.getUuid(), self.getManagementIp(), connectPath, rsp.getError());
            } else {
                VersionComparator libvirtVersion = new VersionComparator(rsp.getLibvirtVersion());
                VersionComparator qemuVersion = new VersionComparator(rsp.getQemuVersion());
                boolean liveSnapshot = libvirtVersion.compare(KVMConstant.MIN_LIBVIRT_LIVESNAPSHOT_VERSION) >= 0
                        && qemuVersion.compare(KVMConstant.MIN_QEMU_LIVESNAPSHOT_VERSION) >= 0;

                String hostOS = HostSystemTags.OS_DISTRIBUTION.getTokenByResourceUuid(self.getUuid(), HostSystemTags.OS_DISTRIBUTION_TOKEN);
                //liveSnapshot = liveSnapshot && (!"CentOS".equals(hostOS) || KVMGlobalConfig.ALLOW_LIVE_SNAPSHOT_ON_REDHAT.value(Boolean.class));

                if (liveSnapshot) {
                    logger.debug(String.format("kvm host[OS:%s, uuid:%s, name:%s, ip:%s] supports live snapshot with libvirt[version:%s], qemu[version:%s]",
                            hostOS, self.getUuid(), self.getName(), self.getManagementIp(), rsp.getLibvirtVersion(), rsp.getQemuVersion()));

                    recreateNonInherentTag(HostSystemTags.LIVE_SNAPSHOT);
                } else {
                    HostSystemTags.LIVE_SNAPSHOT.deleteInherentTag(self.getUuid());
                }
            }
        } catch (RestClientException e) {
            errCode = operr("unable to connect to kvm host[uuid:%s, ip:%s, url:%s], because %s", self.getUuid(), self.getManagementIp(),
                    connectPath, e.getMessage());
        } catch (Throwable t) {
            logger.warn(t.getMessage(), t);
            errCode = inerr(t.getMessage());
        }

        return errCode;
    }

    private KVMHostVO getSelf() {
        return (KVMHostVO) self;
    }

    private void continueConnect(final boolean newAdded, final Completion completion) {
        ErrorCode errCode = connectToAgent();
        if (errCode != null) {
            throw new OperationFailureException(errCode);
        }

        FlowChain chain = FlowChainBuilder.newSimpleFlowChain();
        chain.setName(String.format("continue-connecting-kvm-host-%s-%s", self.getManagementIp(), self.getUuid()));
        chain.allowWatch();
        for (KVMHostConnectExtensionPoint extp : factory.getConnectExtensions()) {
            KVMHostConnectedContext ctx = new KVMHostConnectedContext();
            ctx.setInventory((KVMHostInventory) getSelfInventory());
            ctx.setNewAddedHost(newAdded);

            chain.then(extp.createKvmHostConnectingFlow(ctx));
        }

        chain.done(new FlowDoneHandler(completion) {
            @Override
            public void handle(Map data) {
                if (noStorageAccessible()){
                    completion.fail(operr("host can not access any primary storage, please check network"));
                } else {
                    completion.success();
                }
            }
        }).error(new FlowErrorHandler(completion) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                completion.fail(err(HostErrors.CONNECTION_ERROR, errCode, "connection error for KVM host[uuid:%s, ip:%s]", self.getUuid(),
                        self.getManagementIp()));
            }
        }).start();
    }

    @Transactional(readOnly = true)
    private boolean noStorageAccessible(){
        // detach ps will delete PrimaryStorageClusterRefVO first.
        List<String> attachedPsUuids = Q.New(PrimaryStorageClusterRefVO.class)
                .select(PrimaryStorageClusterRefVO_.primaryStorageUuid)
                .eq(PrimaryStorageClusterRefVO_.clusterUuid, self.getClusterUuid())
                .listValues();

        long attachedPsCount = attachedPsUuids.size();
        long inaccessiblePsCount = attachedPsCount == 0 ? 0 : Q.New(PrimaryStorageHostRefVO.class)
                .eq(PrimaryStorageHostRefVO_.hostUuid, self.getUuid())
                .eq(PrimaryStorageHostRefVO_.status, PrimaryStorageHostStatus.Disconnected)
                .in(PrimaryStorageHostRefVO_.primaryStorageUuid, attachedPsUuids)
                .count();

        return inaccessiblePsCount == attachedPsCount && attachedPsCount > 0;
    }

    private void createHostVersionSystemTags(String distro, String release, String version) {
        createTagWithoutNonValue(HostSystemTags.OS_DISTRIBUTION, HostSystemTags.OS_DISTRIBUTION_TOKEN, distro, true);
        createTagWithoutNonValue(HostSystemTags.OS_RELEASE, HostSystemTags.OS_RELEASE_TOKEN, release, true);
        createTagWithoutNonValue(HostSystemTags.OS_VERSION, HostSystemTags.OS_VERSION_TOKEN, version, true);
    }

    private void createTagWithoutNonValue(SystemTag tag, String token, String value, boolean inherent) {
        if (value == null) {
            return;
        }
        recreateTag(tag, token, value, inherent);
    }

    private void recreateNonInherentTag(SystemTag tag, String token, String value) {
        recreateTag(tag, token, value, false);
    }

    private void recreateNonInherentTag(SystemTag tag) {
        recreateTag(tag, null, null, false);
    }

    private void recreateInherentTag(SystemTag tag, String token, String value) {
        recreateTag(tag, token, value, true);
    }

    private void recreateTag(SystemTag tag, String token, String value, boolean inherent) {
        SystemTagCreator creator = tag.newSystemTagCreator(self.getUuid());
        Optional.ofNullable(token).ifPresent(it -> creator.setTagByTokens(Collections.singletonMap(token, value)));
        creator.inherent = inherent;
        creator.recreate = true;
        creator.create();
    }

    @Override
    public void connectHook(final ConnectHostInfo info, final Completion complete) {
        if (CoreGlobalProperty.UNIT_TEST_ON) {
            if (info.isNewAdded()) {
                createHostVersionSystemTags("zstack", "kvmSimulator", tester.get(ZTester.KVM_HostVersion, "0.1", String.class));
                if (null == KVMSystemTags.LIBVIRT_VERSION.getTokenByResourceUuid(self.getUuid(), KVMSystemTags.LIBVIRT_VERSION_TOKEN)) {
                    createTagWithoutNonValue(KVMSystemTags.LIBVIRT_VERSION, KVMSystemTags.LIBVIRT_VERSION_TOKEN, tester.get(ZTester.KVM_LibvirtVersion, "1.2.9", String.class), true);
                }
                if (null == KVMSystemTags.QEMU_IMG_VERSION.getTokenByResourceUuid(self.getUuid(), KVMSystemTags.QEMU_IMG_VERSION_TOKEN)) {
                    createTagWithoutNonValue(KVMSystemTags.QEMU_IMG_VERSION, KVMSystemTags.QEMU_IMG_VERSION_TOKEN, tester.get(ZTester.KVM_QemuImageVersion, "2.0.0", String.class), true);

                }
                if (null == KVMSystemTags.CPU_MODEL_NAME.getTokenByResourceUuid(self.getUuid(), KVMSystemTags.CPU_MODEL_NAME_TOKEN)) {
                    createTagWithoutNonValue(KVMSystemTags.CPU_MODEL_NAME, KVMSystemTags.CPU_MODEL_NAME_TOKEN, tester.get(ZTester.KVM_CpuModelName, "Broadwell", String.class), true);
                }

                if (!checkQemuLibvirtVersionOfHost()) {
                    complete.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because qemu/libvirt version does not match",
                            self.getUuid(), self.getClusterUuid()));
                    return;
                }

                if (KVMSystemTags.CHECK_CLUSTER_CPU_MODEL.hasTag(self.getClusterUuid())) {
                    if (KVMSystemTags.CHECK_CLUSTER_CPU_MODEL
                            .getTokenByResourceUuid(self.getClusterUuid(), KVMSystemTags.CHECK_CLUSTER_CPU_MODEL_TOKEN)
                            .equals("true")
                            && !checkCpuModelOfHost()) {
                        complete.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because cpu model name does not match",
                                self.getUuid(), self.getClusterUuid()));
                        return;
                    }

                    complete.success();
                    return;
                }


                if (KVMGlobalConfig.CHECK_HOST_CPU_MODEL_NAME.value(Boolean.class) && !checkCpuModelOfHost()) {
                    complete.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because cpu model name does not match",
                            self.getUuid(), self.getClusterUuid()));
                    return;
                }
            }

            continueConnect(info.isNewAdded(), complete);
        } else {
            FlowChain chain = FlowChainBuilder.newShareFlowChain();
            chain.setName(String.format("run-ansible-for-kvm-%s", self.getUuid()));
            chain.allowWatch();
            chain.then(new ShareFlow() {
                boolean deployed = false;
                @Override
                public void setup() {
                    flow(new NoRollbackFlow() {
                        String __name__ = "test-if-ssh-port-open";

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            long timeout = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(KVMGlobalConfig.TEST_SSH_PORT_ON_OPEN_TIMEOUT.value(Long.class));
                            long ctimeout = TimeUnit.SECONDS.toMillis(KVMGlobalConfig.TEST_SSH_PORT_ON_CONNECT_TIMEOUT.value(Integer.class).longValue());

                            thdf.submitCancelablePeriodicTask(new CancelablePeriodicTask(trigger) {
                                @Override
                                public boolean run() {
                                    if (testPort()) {
                                        trigger.next();
                                        return true;
                                    }

                                    return ifTimeout();
                                }

                                private boolean testPort() {
                                    if (!NetworkUtils.isRemotePortOpen(getSelf().getManagementIp(), getSelf().getPort(), (int) ctimeout)) {
                                        logger.debug(String.format("host[uuid:%s, name:%s, ip:%s]'s ssh port[%s] is not ready yet", getSelf().getUuid(), getSelf().getName(), getSelf().getManagementIp(), getSelf().getPort()));
                                        return false;
                                    } else {
                                        return true;
                                    }
                                }

                                private boolean ifTimeout() {
                                    if (System.currentTimeMillis() > timeout) {
                                        trigger.fail(operr("the host' ssh port[%s] not open after %s seconds, connect timeout", getSelf().getPort(), KVMGlobalConfig.TEST_SSH_PORT_ON_OPEN_TIMEOUT.value(Long.class)));
                                        return true;
                                    } else {
                                        return false;
                                    }
                                }

                                @Override
                                public TimeUnit getTimeUnit() {
                                    return TimeUnit.SECONDS;
                                }

                                @Override
                                public long getInterval() {
                                    return 2;
                                }

                                @Override
                                public String getName() {
                                    return "test-ssh-port-open-for-kvm-host";
                                }
                            });
                        }
                    });

                    if (info.isNewAdded()) {

                        if ((!AnsibleGlobalProperty.ZSTACK_REPO.contains("zstack-mn")) && (!AnsibleGlobalProperty.ZSTACK_REPO.equals("false"))) {
                            flow(new NoRollbackFlow() {
                                String __name__ = "ping-DNS-check-list";

                                @Override
                                public void run(FlowTrigger trigger, Map data) {
                                    String checkList;
                                    if (AnsibleGlobalProperty.ZSTACK_REPO.contains(KVMConstant.ALI_REPO)) {
                                        checkList = KVMGlobalConfig.HOST_DNS_CHECK_ALIYUN.value();
                                    } else if (AnsibleGlobalProperty.ZSTACK_REPO.contains(KVMConstant.NETEASE_REPO)) {
                                        checkList = KVMGlobalConfig.HOST_DNS_CHECK_163.value();
                                    } else {
                                        checkList = KVMGlobalConfig.HOST_DNS_CHECK_LIST.value();
                                    }

                                    checkList = checkList.replaceAll(",", " ");

                                    SshShell sshShell = new SshShell();
                                    sshShell.setHostname(getSelf().getManagementIp());
                                    sshShell.setUsername(getSelf().getUsername());
                                    sshShell.setPassword(getSelf().getPassword());
                                    sshShell.setPort(getSelf().getPort());
                                    SshResult ret = sshShell.runScriptWithToken("scripts/check-public-dns-name.sh",
                                            map(e("dnsCheckList", checkList)));

                                    if (ret.isSshFailure()) {
                                        trigger.fail(operr("unable to connect to KVM[ip:%s, username:%s, sshPort: %d, ] to do DNS check, please check if username/password is wrong; %s", self.getManagementIp(), getSelf().getUsername(), getSelf().getPort(), ret.getExitErrorMessage()));
                                    } else if (ret.getReturnCode() != 0) {
                                        trigger.fail(operr("failed to ping all DNS/IP in %s; please check /etc/resolv.conf to make sure your host is able to reach public internet", checkList));
                                    } else {
                                        trigger.next();
                                    }
                                }
                            });
                        }
                    }

                    flow(new NoRollbackFlow() {
                        String __name__ = "check-if-host-can-reach-management-node";

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            SshShell sshShell = new SshShell();
                            sshShell.setHostname(getSelf().getManagementIp());
                            sshShell.setUsername(getSelf().getUsername());
                            sshShell.setPassword(getSelf().getPassword());
                            sshShell.setPort(getSelf().getPort());
                            ShellUtils.run(String.format("arp -d %s || true", getSelf().getManagementIp()));
                            SshResult ret = sshShell.runCommand(String.format("curl --connect-timeout 10 %s", restf.getCallbackUrl()));

                            if (ret.isSshFailure()) {
                                throw new OperationFailureException(operr("unable to connect to KVM[ip:%s, username:%s, sshPort:%d] to check the management node connectivity," +
                                                "please check if username/password is wrong; %s", self.getManagementIp(), getSelf().getUsername(), getSelf().getPort(), ret.getExitErrorMessage()));
                            } else if (ret.getReturnCode() != 0) {
                                throw new OperationFailureException(operr("the KVM host[ip:%s] cannot access the management node's callback url. It seems" +
                                                " that the KVM host cannot reach the management IP[%s]. %s %s", self.getManagementIp(), restf.getHostName(),
                                        ret.getStderr(), ret.getExitErrorMessage()));
                            }

                            trigger.next();
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "apply-ansible-playbook";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            String srcPath = PathUtil.findFileOnClassPath(String.format("ansible/kvm/%s", agentPackageName), true).getAbsolutePath();
                            String destPath = String.format("/var/lib/zstack/kvm/package/%s", agentPackageName);
                            SshFileMd5Checker checker = new SshFileMd5Checker();
                            checker.setUsername(getSelf().getUsername());
                            checker.setPassword(getSelf().getPassword());
                            checker.setSshPort(getSelf().getPort());
                            checker.setTargetIp(getSelf().getManagementIp());
                            checker.addSrcDestPair(SshFileMd5Checker.ZSTACKLIB_SRC_PATH, String.format("/var/lib/zstack/kvm/package/%s", AnsibleGlobalProperty.ZSTACKLIB_PACKAGE_NAME));
                            checker.addSrcDestPair(srcPath, destPath);

                            SshChronyConfigChecker chronyChecker = new SshChronyConfigChecker();
                            chronyChecker.setTargetIp(getSelf().getManagementIp());
                            chronyChecker.setUsername(getSelf().getUsername());
                            chronyChecker.setPassword(getSelf().getPassword());
                            chronyChecker.setSshPort(getSelf().getPort());

                            SshYumRepoChecker repoChecker = new SshYumRepoChecker();
                            repoChecker.setTargetIp(getSelf().getManagementIp());
                            repoChecker.setUsername(getSelf().getUsername());
                            repoChecker.setPassword(getSelf().getPassword());
                            repoChecker.setSshPort(getSelf().getPort());

                            CallBackNetworkChecker callbackChecker = new CallBackNetworkChecker();
                            callbackChecker.setTargetIp(getSelf().getManagementIp());
                            callbackChecker.setUsername(getSelf().getUsername());
                            callbackChecker.setPassword(getSelf().getPassword());
                            callbackChecker.setPort(getSelf().getPort());
                            callbackChecker.setCallbackIp(Platform.getManagementServerIp());
                            callbackChecker.setCallBackPort(CloudBusGlobalProperty.HTTP_PORT);

                            AnsibleRunner runner = new AnsibleRunner();
                            runner.installChecker(checker);
                            runner.installChecker(chronyChecker);
                            runner.installChecker(repoChecker);
                            runner.installChecker(callbackChecker);
                            runner.setAgentPort(KVMGlobalProperty.AGENT_PORT);
                            runner.setTargetIp(getSelf().getManagementIp());
                            runner.setPlayBookName(KVMConstant.ANSIBLE_PLAYBOOK_NAME);
                            runner.setUsername(getSelf().getUsername());
                            runner.setPassword(getSelf().getPassword());
                            runner.setSshPort(getSelf().getPort());
                            if (info.isNewAdded()) {
                                runner.putArgument("init", "true");
                                runner.setFullDeploy(true);
                            }
                            if (NetworkGlobalProperty.SKIP_IPV6) {
                                runner.putArgument("skipIpv6", "true");
                            }
                            runner.putArgument("pkg_kvmagent", agentPackageName);
                            runner.putArgument("hostname", String.format("%s.zstack.org", self.getManagementIp().replaceAll("\\.", "-")));
                            if (CoreGlobalProperty.SYNC_NODE_TIME) {
                                if (CoreGlobalProperty.CHRONY_SERVERS == null || CoreGlobalProperty.CHRONY_SERVERS.isEmpty()) {
                                    trigger.fail(operr("chrony server not configured!"));
                                    return;
                                }
                                runner.putArgument("chrony_servers", String.join(",", CoreGlobalProperty.CHRONY_SERVERS));
                            }
                            runner.putArgument("update_packages", String.valueOf(CoreGlobalProperty.UPDATE_PKG_WHEN_CONNECT));

                            UriComponentsBuilder ub = UriComponentsBuilder.fromHttpUrl(restf.getBaseUrl());
                            ub.path(new StringBind(KVMConstant.KVM_ANSIBLE_LOG_PATH_FROMAT).bind("uuid", self.getUuid()).toString());
                            String postUrl = ub.build().toString();

                            runner.putArgument("post_url", postUrl);
                            runner.run(new ReturnValueCompletion<Boolean>(trigger) {
                                @Override
                                public void success(Boolean run) {
                                    if (run != null) {
                                        deployed = run;
                                    }
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "echo-host";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            restf.echo(echoPath, new Completion(trigger) {
                                @Override
                                public void success() {
                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    boolean needRestart = KVMGlobalConfig.RESTART_AGENT_IF_FAKE_DEAD.value(Boolean.class);
                                    if (!deployed && needRestart) {
                                        // if not deployed and echo failed, we thought it is fake dead, see: ZSTACK-18628
                                        AnsibleRunner runner = new AnsibleRunner();
                                        runner.setAgentPort(KVMGlobalProperty.AGENT_PORT);
                                        runner.setTargetIp(getSelf().getManagementIp());
                                        runner.setUsername(getSelf().getUsername());
                                        runner.setPassword(getSelf().getPassword());
                                        runner.setSshPort(getSelf().getPort());

                                        runner.restartAgent(AnsibleConstant.KVM_AGENT_NAME, new Completion(trigger) {
                                            @Override
                                            public void success() {
                                                restf.echo(echoPath, new Completion(trigger) {
                                                    @Override
                                                    public void success() {
                                                        trigger.next();
                                                    }

                                                    @Override
                                                    public void fail(ErrorCode errorCode) {
                                                        trigger.fail(errorCode);
                                                    }
                                                });
                                            }

                                            @Override
                                            public void fail(ErrorCode errorCode) {
                                                trigger.fail(errorCode);
                                            }
                                        });
                                    } else {
                                        trigger.fail(errorCode);
                                    }
                                }
                            });
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "update-kvmagent-dependencies";

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            if (!CoreGlobalProperty.UPDATE_PKG_WHEN_CONNECT) {
                                trigger.next();
                                return;
                            }

                            UpdateDependencyCmd cmd = new UpdateDependencyCmd();
                            cmd.hostUuid = self.getUuid();
                            new Http<>(updateDependencyPath, cmd, UpdateDependencyRsp.class)
                                    .call(new ReturnValueCompletion<UpdateDependencyRsp>(trigger) {
                                        @Override
                                        public void success(UpdateDependencyRsp ret) {
                                            if (ret.isSuccess()) {
                                                trigger.next();
                                            } else {
                                                trigger.fail(Platform.operr("%s", ret.getError()));
                                            }
                                        }

                                        @Override
                                        public void fail(ErrorCode errorCode) {
                                            trigger.fail(errorCode);
                                        }
                                    });
                        }
                    });

                    flow(new NoRollbackFlow() {
                        String __name__ = "collect-kvm-host-facts";

                        @Override
                        public void run(final FlowTrigger trigger, Map data) {
                            HostFactCmd cmd = new HostFactCmd();
                            new Http<>(hostFactPath, cmd, HostFactResponse.class)
                                    .call(new ReturnValueCompletion<HostFactResponse>(trigger) {
                                @Override
                                public void success(HostFactResponse ret) {
                                    if (!ret.isSuccess()) {
                                        trigger.fail(operr("operation error, because:%s", ret.getError()));
                                        return;
                                    }

                                    if (ret.getHvmCpuFlag() == null) {
                                        trigger.fail(operr("cannot find either 'vmx' or 'svm' in /proc/cpuinfo, please make sure you have enabled virtualization in your BIOS setting"));
                                        return;
                                    }

                                    // create system tags of os::version etc
                                    createHostVersionSystemTags(ret.getOsDistribution(), ret.getOsRelease(), ret.getOsVersion());

                                    createTagWithoutNonValue(KVMSystemTags.QEMU_IMG_VERSION, KVMSystemTags.QEMU_IMG_VERSION_TOKEN, ret.getQemuImgVersion(), false);
                                    createTagWithoutNonValue(KVMSystemTags.LIBVIRT_VERSION, KVMSystemTags.LIBVIRT_VERSION_TOKEN, ret.getLibvirtVersion(), false);
                                    createTagWithoutNonValue(KVMSystemTags.HVM_CPU_FLAG, KVMSystemTags.HVM_CPU_FLAG_TOKEN, ret.getHvmCpuFlag(), false);
                                    createTagWithoutNonValue(KVMSystemTags.CPU_MODEL_NAME, KVMSystemTags.CPU_MODEL_NAME_TOKEN, ret.getCpuModelName(), false);
                                    createTagWithoutNonValue(HostSystemTags.HOST_CPU_MODEL_NAME, HostSystemTags.HOST_CPU_MODEL_NAME_TOKEN, ret.getHostCpuModelName(), true);
                                    createTagWithoutNonValue(HostSystemTags.CPU_GHZ, HostSystemTags.CPU_GHZ_TOKEN, ret.getCpuGHz(), true);
                                    createTagWithoutNonValue(HostSystemTags.SYSTEM_PRODUCT_NAME, HostSystemTags.SYSTEM_PRODUCT_NAME_TOKEN, ret.getSystemProductName(), true);
                                    createTagWithoutNonValue(HostSystemTags.SYSTEM_SERIAL_NUMBER, HostSystemTags.SYSTEM_SERIAL_NUMBER_TOKEN, ret.getSystemSerialNumber(), true);

                                    if (ret.getLibvirtVersion().compareTo(KVMConstant.MIN_LIBVIRT_VIRTIO_SCSI_VERSION) >= 0) {
                                        recreateNonInherentTag(KVMSystemTags.VIRTIO_SCSI);
                                    }



                                    List<String> ips = ret.getIpAddresses();
                                    if (ips != null) {
                                        ips.remove(self.getManagementIp());
                                        if (CoreGlobalProperty.MN_VIP != null) {
                                            ips.remove(CoreGlobalProperty.MN_VIP);
                                        }
                                        if (!ips.isEmpty()) {
                                            recreateNonInherentTag(HostSystemTags.EXTRA_IPS, HostSystemTags.EXTRA_IPS_TOKEN, StringUtils.join(ips, ","));
                                        } else {
                                            HostSystemTags.EXTRA_IPS.delete(self.getUuid());
                                        }
                                    }

                                    trigger.next();
                                }

                                @Override
                                public void fail(ErrorCode errorCode) {
                                    trigger.fail(errorCode);
                                }
                            });
                        }
                    });

                    if (info.isNewAdded()) {
                        flow(new NoRollbackFlow() {
                            String __name__ = "check-qemu-libvirt-version";

                            @Override
                            public void run(FlowTrigger trigger, Map data) {
                                if (!checkQemuLibvirtVersionOfHost()) {
                                    trigger.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because qemu/libvirt version does not match",
                                            self.getUuid(), self.getClusterUuid()));
                                    return;
                                }

                                if (KVMSystemTags.CHECK_CLUSTER_CPU_MODEL.hasTag(self.getClusterUuid())) {
                                    if (KVMSystemTags.CHECK_CLUSTER_CPU_MODEL
                                            .getTokenByResourceUuid(self.getClusterUuid(), KVMSystemTags.CHECK_CLUSTER_CPU_MODEL_TOKEN)
                                            .equals("true")
                                            && !checkCpuModelOfHost()) {
                                        trigger.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because cpu model name does not match",
                                                self.getUuid(), self.getClusterUuid()));
                                        return;
                                    }

                                    trigger.next();
                                    return;
                                }

                                if (KVMGlobalConfig.CHECK_HOST_CPU_MODEL_NAME.value(Boolean.class) && !checkCpuModelOfHost()) {
                                    trigger.fail(operr("host [uuid:%s] cannot be added to cluster [uuid:%s] because cpu model name does not match",
                                            self.getUuid(), self.getClusterUuid()));
                                    return;
                                }

                                trigger.next();
                            }
                        });
                    }

                    flow(new NoRollbackFlow() {
                        String __name__ = "prepare-host-env";

                        @Override
                        public void run(FlowTrigger trigger, Map data) {
                            String script = "which iptables > /dev/null && iptables -C FORWARD -j REJECT --reject-with icmp-host-prohibited > /dev/null 2>&1 && iptables -D FORWARD -j REJECT --reject-with icmp-host-prohibited > /dev/null 2>&1 || true";
                            runShell(script);
                            trigger.next();
                        }
                    });

                    error(new FlowErrorHandler(complete) {
                        @Override
                        public void handle(ErrorCode errCode, Map data) {
                            complete.fail(errCode);
                        }
                    });

                    done(new FlowDoneHandler(complete) {
                        @Override
                        public void handle(Map data) {
                            continueConnect(info.isNewAdded(), complete);
                        }
                    });
                }
            }).start();
        }
    }

    private boolean checkCpuModelOfHost() {
        List<String> hostUuidsInCluster = Q.New(HostVO.class)
                .select(HostVO_.uuid)
                .eq(HostVO_.clusterUuid, self.getClusterUuid())
                .notEq(HostVO_.uuid, self.getUuid())
                .listValues();
        if (hostUuidsInCluster.isEmpty()) {
            return true;
        }

        Map<String, List<String>> cpuModelNames = KVMSystemTags.CPU_MODEL_NAME.getTags(hostUuidsInCluster);
        if (cpuModelNames != null && cpuModelNames.size() != 0) {
            String clusterCpuModelName = KVMSystemTags.CPU_MODEL_NAME.getTokenByTag(
                    cpuModelNames.values().iterator().next().get(0),
                    KVMSystemTags.CPU_MODEL_NAME_TOKEN
            );

            String hostCpuModelName = KVMSystemTags.CPU_MODEL_NAME.getTokenByResourceUuid(
                    self.getUuid(), KVMSystemTags.CPU_MODEL_NAME_TOKEN
            );

            if (clusterCpuModelName != null && !clusterCpuModelName.equals(hostCpuModelName)) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected void updateOsHook(String exclude, Completion completion) {
        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("update-operating-system-for-host-%s", self.getUuid()));
        chain.then(new ShareFlow() {
            // is the host in maintenance already?
            HostState oldState = self.getState();
            boolean maintenance = oldState == HostState.Maintenance;

            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "double-check-host-state-status";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        if (self.getState() == HostState.PreMaintenance) {
                            trigger.fail(Platform.operr("host is in the premaintenance state, cannot update os"));
                        } else if (self.getStatus() != HostStatus.Connected) {
                            trigger.fail(Platform.operr("host is not in the connected status, cannot update os"));
                        } else {
                            trigger.next();
                        }
                    }
                });

                flow(new Flow() {
                    String __name__ = "make-host-in-maintenance";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        if (maintenance) {
                            trigger.next();
                            return;
                        }

                        // enter maintenance, but donot stop/migrate vm on the host
                        ChangeHostStateMsg cmsg = new ChangeHostStateMsg();
                        cmsg.setUuid(self.getUuid());
                        cmsg.setStateEvent(HostStateEvent.preMaintain.toString());
                        bus.makeTargetServiceIdByResourceUuid(cmsg, HostConstant.SERVICE_ID, self.getUuid());
                        bus.send(cmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (reply.isSuccess()) {
                                    trigger.next();
                                } else {
                                    trigger.fail(reply.getError());
                                }
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (maintenance) {
                            trigger.rollback();
                            return;
                        }

                        // back to old host state
                        if (oldState == HostState.Disabled) {
                            changeState(HostStateEvent.disable);
                        } else {
                            changeState(HostStateEvent.enable);
                        }
                        trigger.rollback();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "update-host-os";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        UpdateHostOSCmd cmd = new UpdateHostOSCmd();
                        cmd.hostUuid = self.getUuid();
                        cmd.excludePackages = exclude;

                        new Http<>(updateHostOSPath, cmd, UpdateHostOSRsp.class)
                                .call(new ReturnValueCompletion<UpdateHostOSRsp>(trigger) {
                            @Override
                            public void success(UpdateHostOSRsp ret) {
                                if (ret.isSuccess()) {
                                    trigger.next();
                                } else {
                                    trigger.fail(Platform.operr("%s", ret.getError()));
                                }
                            }

                            @Override
                            public void fail(ErrorCode errorCode) {
                                trigger.fail(errorCode);
                            }
                        });
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "recover-host-state";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        if (maintenance) {
                            trigger.next();
                            return;
                        }

                        // back to old host state
                        if (oldState == HostState.Disabled) {
                            changeState(HostStateEvent.disable);
                        } else {
                            changeState(HostStateEvent.enable);
                        }
                        trigger.next();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "auto-reconnect-host";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        ReconnectHostMsg rmsg = new ReconnectHostMsg();
                        rmsg.setHostUuid(self.getUuid());
                        bus.makeTargetServiceIdByResourceUuid(rmsg, HostConstant.SERVICE_ID, self.getUuid());
                        bus.send(rmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (reply.isSuccess()) {
                                    logger.info("successfully reconnected host " + self.getUuid());
                                } else {
                                    logger.error("failed to reconnect host " + self.getUuid());
                                }
                                trigger.next();
                            }
                        });
                    }
                });

                done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        logger.debug(String.format("successfully updated operating system for host[uuid:%s]", self.getUuid()));
                        completion.success();
                    }
                });

                error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        logger.warn(String.format("failed to updated operating system for host[uuid:%s] because %s",
                                self.getUuid(), errCode.getDetails()));
                        completion.fail(errCode);
                    }
                });
            }
        }).start();
    }

    private boolean checkMigrateNetworkCidrOfHost(String cidr) {
        if (NetworkUtils.isIpv4InCidr(self.getManagementIp(), cidr)) {
            return true;
        }

        final String extraIps = HostSystemTags.EXTRA_IPS.getTokenByResourceUuid(
                self.getUuid(), HostSystemTags.EXTRA_IPS_TOKEN);
        if (extraIps == null) {
            logger.error(String.format("Host[uuid:%s] has no IPs in migrate network", self.getUuid()));
            return false;
        }

        final String[] ips = extraIps.split(",");
        for (String ip: ips) {
            if (NetworkUtils.isIpv4InCidr(ip, cidr)) {
                return true;
            }
        }

        return false;
    }

    private boolean checkQemuLibvirtVersionOfHost() {
        List<String> hostUuidsInCluster = Q.New(HostVO.class)
                .select(HostVO_.uuid)
                .eq(HostVO_.clusterUuid, self.getClusterUuid())
                .notEq(HostVO_.uuid, self.getUuid())
                .listValues();
        if (hostUuidsInCluster.isEmpty()) {
            return true;
        }

        Map<String, List<String>> qemuVersions = KVMSystemTags.QEMU_IMG_VERSION.getTags(hostUuidsInCluster);
        if (qemuVersions != null && qemuVersions.size() != 0) {
            String clusterQemuVer = KVMSystemTags.QEMU_IMG_VERSION.getTokenByTag(
                    qemuVersions.values().iterator().next().get(0),
                    KVMSystemTags.QEMU_IMG_VERSION_TOKEN
            );

            String hostQemuVer = KVMSystemTags.QEMU_IMG_VERSION.getTokenByResourceUuid(
                    self.getUuid(), KVMSystemTags.QEMU_IMG_VERSION_TOKEN
            );

            if (clusterQemuVer != null && !clusterQemuVer.equals(hostQemuVer)) {
                return false;
            }
        }

        Map<String, List<String>> libvirtVersions = KVMSystemTags.LIBVIRT_VERSION.getTags(hostUuidsInCluster);
        if (libvirtVersions != null && libvirtVersions.size() != 0) {
            String clusterLibvirtVer = KVMSystemTags.LIBVIRT_VERSION.getTokenByTag(
                    libvirtVersions.values().iterator().next().get(0),
                    KVMSystemTags.LIBVIRT_VERSION_TOKEN
            );

            String hostLibvirtVer = KVMSystemTags.LIBVIRT_VERSION.getTokenByResourceUuid(
                    self.getUuid(), KVMSystemTags.LIBVIRT_VERSION_TOKEN
            );

            if (clusterLibvirtVer != null && !clusterLibvirtVer.equals(hostLibvirtVer)) {
                return false;
            }
        }

        return true;
    }

    @Override
    protected int getHostSyncLevel() {
        return KVMGlobalConfig.HOST_SYNC_LEVEL.value(Integer.class);
    }

    @Override
    protected HostVO updateHost(APIUpdateHostMsg msg) {
        if (!(msg instanceof APIUpdateKVMHostMsg)) {
            return super.updateHost(msg);
        }

        KVMHostVO vo = (KVMHostVO) super.updateHost(msg);
        vo = vo == null ? getSelf() : vo;

        APIUpdateKVMHostMsg umsg = (APIUpdateKVMHostMsg) msg;
        if (umsg.getUsername() != null) {
            vo.setUsername(umsg.getUsername());
        }
        if (umsg.getPassword() != null) {
            vo.setPassword(umsg.getPassword());
        }
        if (umsg.getSshPort() != null && umsg.getSshPort() > 0 && umsg.getSshPort() <= 65535) {
            vo.setPort(umsg.getSshPort());
        }

        return vo;
    }
}
