package org.zstack.compute.vm;

import com.mysql.jdbc.exceptions.jdbc4.MySQLIntegrityConstraintViolationException;
import org.apache.commons.validator.routines.DomainValidator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaSystemException;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.compute.allocator.HostAllocatorManager;
import org.zstack.core.Platform;
import org.zstack.core.asyncbatch.While;
import org.zstack.core.cloudbus.*;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.config.GlobalConfig;
import org.zstack.core.config.GlobalConfigUpdateExtensionPoint;
import org.zstack.core.db.*;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.errorcode.ErrorFacade;
import org.zstack.core.jsonlabel.JsonLabel;
import org.zstack.core.thread.*;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.header.AbstractService;
import org.zstack.header.allocator.AllocateHostDryRunReply;
import org.zstack.header.allocator.DesignatedAllocateHostMsg;
import org.zstack.header.allocator.HostAllocatorConstant;
import org.zstack.header.apimediator.ApiMessageInterceptionException;
import org.zstack.header.apimediator.GlobalApiMessageInterceptor;
import org.zstack.header.cluster.ClusterInventory;
import org.zstack.header.cluster.ClusterVO;
import org.zstack.header.configuration.*;
import org.zstack.header.core.Completion;
import org.zstack.header.core.NoErrorCompletion;
import org.zstack.header.core.NopeNoErrorCompletion;
import org.zstack.header.core.ReturnValueCompletion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.exception.CloudConfigureFailException;
import org.zstack.header.exception.CloudRuntimeException;
import org.zstack.header.host.AfterChangeHostStatusExtensionPoint;
import org.zstack.header.host.HostAfterConnectedExtensionPoint;
import org.zstack.header.host.HostInventory;
import org.zstack.header.host.HostStatus;
import org.zstack.header.identity.*;
import org.zstack.header.identity.Quota.QuotaOperator;
import org.zstack.header.identity.Quota.QuotaPair;
import org.zstack.header.image.*;
import org.zstack.header.image.ImageConstant.ImageMediaType;
import org.zstack.header.managementnode.ManagementNodeReadyExtensionPoint;
import org.zstack.header.message.*;
import org.zstack.header.network.l3.*;
import org.zstack.header.storage.backup.BackupStorageType;
import org.zstack.header.storage.backup.BackupStorageVO;
import org.zstack.header.storage.primary.*;
import org.zstack.header.tag.SystemTagCreateMessageValidator;
import org.zstack.header.tag.SystemTagVO;
import org.zstack.header.tag.SystemTagValidator;
import org.zstack.header.vm.*;
import org.zstack.header.vm.VmInstanceConstant.VmOperation;
import org.zstack.header.vm.VmInstanceDeletionPolicyManager.VmInstanceDeletionPolicy;
import org.zstack.header.vm.cdrom.VmCdRomVO;
import org.zstack.header.vm.cdrom.VmCdRomVO_;
import org.zstack.header.volume.*;
import org.zstack.header.zone.ZoneInventory;
import org.zstack.header.zone.ZoneVO;
import org.zstack.identity.AccountManager;
import org.zstack.identity.QuotaUtil;
import org.zstack.network.l3.L3NetworkManager;
import org.zstack.tag.SystemTagUtils;
import org.zstack.tag.TagManager;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.ObjectUtils;
import org.zstack.utils.TagUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;
import org.zstack.utils.network.IPv6Constants;
import org.zstack.utils.network.IPv6NetworkUtils;
import org.zstack.utils.network.NetworkUtils;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static java.util.Arrays.asList;
import static org.zstack.core.Platform.*;
import static org.zstack.utils.CollectionDSL.list;

public class VmInstanceManagerImpl extends AbstractService implements
        VmInstanceManager,
        ReportQuotaExtensionPoint,
        ManagementNodeReadyExtensionPoint,
        L3NetworkDeleteExtensionPoint,
        ResourceOwnerAfterChangeExtensionPoint,
        GlobalApiMessageInterceptor,
        HostAfterConnectedExtensionPoint,
        AfterChangeHostStatusExtensionPoint {
    private static final CLogger logger = Utils.getLogger(VmInstanceManagerImpl.class);
    private Map<String, VmInstanceFactory> vmInstanceFactories = Collections.synchronizedMap(new HashMap<>());
    private List<String> createVmWorkFlowElements;
    private List<String> stopVmWorkFlowElements;
    private List<String> rebootVmWorkFlowElements;
    private List<String> startVmWorkFlowElements;
    private List<String> destroyVmWorkFlowElements;
    private List<String> migrateVmWorkFlowElements;
    private List<String> attachIsoWorkFlowElements;
    private List<String> detachIsoWorkFlowElements;
    private List<String> attachVolumeWorkFlowElements;
    private List<String> expungeVmWorkFlowElements;
    private List<String> pauseVmWorkFlowElements;
    private List<String> resumeVmWorkFlowElements;
    private FlowChainBuilder createVmFlowBuilder;
    private FlowChainBuilder stopVmFlowBuilder;
    private FlowChainBuilder rebootVmFlowBuilder;
    private FlowChainBuilder startVmFlowBuilder;
    private FlowChainBuilder destroyVmFlowBuilder;
    private FlowChainBuilder migrateVmFlowBuilder;
    private FlowChainBuilder attachVolumeFlowBuilder;
    private FlowChainBuilder attachIsoFlowBuilder;
    private FlowChainBuilder detachIsoFlowBuilder;
    private FlowChainBuilder expungeVmFlowBuilder;
    private FlowChainBuilder pauseVmFlowBuilder;
    private FlowChainBuilder resumeVmFlowBuilder;
    private static final Set<Class> allowedMessageAfterSoftDeletion = new HashSet<>();
    private Future<Void> expungeVmTask;
    private Map<Class, VmInstanceBaseExtensionFactory> vmInstanceBaseExtensionFactories = new HashMap<>();

    static {
        allowedMessageAfterSoftDeletion.add(VmInstanceDeletionMsg.class);
    }

    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private PluginRegistry pluginRgty;
    @Autowired
    private DbEntityLister dl;
    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private TagManager tagMgr;
    @Autowired
    private ErrorFacade errf;
    @Autowired
    private ResourceDestinationMaker destMaker;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private VmInstanceDeletionPolicyManager deletionPolicyMgr;
    @Autowired
    private EventFacade evtf;
    @Autowired
    private HostAllocatorManager hostAllocatorMgr;
    @Autowired
    protected VmInstanceExtensionPointEmitter extEmitter;
    @Autowired
    protected L3NetworkManager l3nm;

    @Override
    public void handleMessage(Message msg) {
        if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    void passThrough(VmInstanceMessage msg) {
        VmInstanceVO vo = dbf.findByUuid(msg.getVmInstanceUuid(), VmInstanceVO.class);
        if (vo == null && allowedMessageAfterSoftDeletion.contains(msg.getClass())) {
            VmInstanceEO eo = dbf.findByUuid(msg.getVmInstanceUuid(), VmInstanceEO.class);
            vo = ObjectUtils.newAndCopy(eo, VmInstanceVO.class);
        }

        if (vo == null) {
            String err = String.format("Cannot find VmInstance[uuid:%s], it may have been deleted", msg.getVmInstanceUuid());
            bus.replyErrorByMessageType((Message) msg, err);
            return;
        }

        VmInstanceFactory factory = getVmInstanceFactory(VmInstanceType.valueOf(vo.getType()));
        VmInstance vm = factory.getVmInstance(vo);
        vm.handleMessage((Message) msg);
    }

    private void handleLocalMessage(Message msg) {
        if (msg instanceof CreateVmInstanceMsg) {
            handle((CreateVmInstanceMsg) msg);
        } else if (msg instanceof DetachIpAddressFromVmNicMsg) {
            handle((DetachIpAddressFromVmNicMsg) msg);
        } else if (msg instanceof AttachL3NetworkToVmNicMsg) {
            handle((AttachL3NetworkToVmNicMsg) msg);
        } else if (msg instanceof VmInstanceMessage) {
            passThrough((VmInstanceMessage) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APICreateVmInstanceMsg) {
            handle((APICreateVmInstanceMsg) msg);
        } else if(msg instanceof APICreateVmNicMsg) {
            handle((APICreateVmNicMsg) msg);
        } else if (msg instanceof APIDeleteVmNicMsg) {
            handle((APIDeleteVmNicMsg) msg);
        } else if (msg instanceof APIAttachL3NetworkToVmNicMsg) {
            handle((APIAttachL3NetworkToVmNicMsg) msg);
        } else if (msg instanceof APIGetCandidateZonesClustersHostsForCreatingVmMsg) {
            handle((APIGetCandidateZonesClustersHostsForCreatingVmMsg) msg);
        } else if (msg instanceof APIGetCandidatePrimaryStoragesForCreatingVmMsg) {
            handle((APIGetCandidatePrimaryStoragesForCreatingVmMsg) msg);
        } else if (msg instanceof APIGetInterdependentL3NetworksImagesMsg) {
            handle((APIGetInterdependentL3NetworksImagesMsg) msg);
        } else if (msg instanceof APIGetCandidateVmForAttachingIsoMsg) {
            handle((APIGetCandidateVmForAttachingIsoMsg) msg);
        } else if (msg instanceof VmInstanceMessage) {
            passThrough((VmInstanceMessage) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    @Transactional(readOnly = true)
    private void handle(APIGetCandidateVmForAttachingIsoMsg msg) {
        APIGetCandidateVmForAttachingIsoReply reply = new APIGetCandidateVmForAttachingIsoReply();

        String sql = "select bs" +
                " from BackupStorageVO bs, ImageBackupStorageRefVO ref" +
                " where ref.imageUuid = :isoUuid" +
                " and bs.uuid = ref.backupStorageUuid";
        TypedQuery<BackupStorageVO> q = dbf.getEntityManager().createQuery(sql, BackupStorageVO.class);
        q.setParameter("isoUuid", msg.getIsoUuid());
        List<BackupStorageVO> bss = q.getResultList();
        if (bss.isEmpty()) {
            reply.setInventories(new ArrayList<>());
            bus.reply(msg, reply);
            return;
        }

        List<String> psUuids = new ArrayList<>();
        List<String> psTypes = new ArrayList<>();
        for (BackupStorageVO bs : bss) {
            BackupStorageType bsType = BackupStorageType.valueOf(bs.getType());
            List<String> lst = bsType.findRelatedPrimaryStorage(bs.getUuid());
            if (lst != null) {
                psUuids.addAll(lst);
            } else {
                psTypes.addAll(hostAllocatorMgr.getPrimaryStorageTypesByBackupStorageTypeFromMetrics(bs.getType()));
            }
        }

        List<VmInstanceVO> vms = new ArrayList<>();
        if (!psUuids.isEmpty()) {
            sql = "select vm" +
                    " from VmInstanceVO vm, VolumeVO vol" +
                    " where vol.type = :volType" +
                    " and vol.vmInstanceUuid = vm.uuid" +
                    " and vm.state in (:vmStates)" +
                    " and vol.primaryStorageUuid in (:psUuids)";
            TypedQuery<VmInstanceVO> vmq = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
            vmq.setParameter("volType", VolumeType.Root);
            vmq.setParameter("vmStates", asList(VmInstanceState.Running, VmInstanceState.Stopped));
            vmq.setParameter("psUuids", psUuids);
            vms.addAll(vmq.getResultList());
        }

        if (!psTypes.isEmpty()) {
            sql = "select vm" +
                    " from VmInstanceVO vm, VolumeVO vol, PrimaryStorageVO ps" +
                    " where vol.type = :volType" +
                    " and vol.vmInstanceUuid = vm.uuid" +
                    " and vm.state in (:vmStates)" +
                    " and vol.primaryStorageUuid = ps.uuid" +
                    " and ps.type in (:psTypes)";
            TypedQuery<VmInstanceVO> vmq = dbf.getEntityManager().createQuery(sql, VmInstanceVO.class);
            vmq.setParameter("volType", VolumeType.Root);
            vmq.setParameter("vmStates", asList(VmInstanceState.Running, VmInstanceState.Stopped));
            vmq.setParameter("psTypes", psTypes);
            vms.addAll(vmq.getResultList());
        }

        List<VmInstanceInventory> result = VmInstanceInventory.valueOf(vms);

        for (VmAttachIsoExtensionPoint ext : pluginRgty.getExtensionList(VmAttachIsoExtensionPoint.class)) {
            ext.filtCandidateVms(msg.getIsoUuid(), result);
        }
        reply.setInventories(result);
        bus.reply(msg, reply);
    }

    private void handle(APIGetInterdependentL3NetworksImagesMsg msg) {
        final String accountUuid = msg.getSession().getAccountUuid();
        if (msg.getImageUuid() != null) {
            getInterdependentL3NetworksByImageUuid(msg, accountUuid);
        } else {
            getInterdependentImagesByL3NetworkUuids(msg);
        }
    }

    private List<BackupStorageVO> listIntersection(List<BackupStorageVO> a, List<BackupStorageVO> b) {
        List<BackupStorageVO> ret = new ArrayList<>();
        for (BackupStorageVO s : a) {
            if (b.stream().filter(it -> it.getUuid().equals(s.getUuid())).findAny().isPresent()) {
                ret.add(s);
            }
        }

        return ret;
    }

    @Transactional(readOnly = true)
    private void getInterdependentImagesByL3NetworkUuids(APIGetInterdependentL3NetworksImagesMsg msg) {
        APIGetInterdependentL3NetworkImageReply reply = new APIGetInterdependentL3NetworkImageReply();

        List<List<BackupStorageVO>> bss = new ArrayList<>();
        for (String l3uuid : msg.getL3NetworkUuids()) {
            String sql = "select ps" +
                    " from PrimaryStorageVO ps, L2NetworkClusterRefVO l2ref," +
                    " L3NetworkVO l3, PrimaryStorageClusterRefVO psref" +
                    " where ps.uuid = psref.primaryStorageUuid" +
                    " and psref.clusterUuid = l2ref.clusterUuid" +
                    " and l2ref.l2NetworkUuid = l3.l2NetworkUuid" +
                    " and l3.uuid = :l3uuid";
            TypedQuery<PrimaryStorageVO> psq = dbf.getEntityManager().createQuery(sql, PrimaryStorageVO.class);
            psq.setParameter("l3uuid", l3uuid);
            List<PrimaryStorageVO> pss = psq.getResultList();

            List<BackupStorageVO> lst = new ArrayList<>();
            for (PrimaryStorageVO ps : pss) {
                PrimaryStorageType psType = PrimaryStorageType.valueOf(ps.getType());
                List<String> bsUuids = psType.findBackupStorage(ps.getUuid());

                if (bsUuids == null) {
                    // the primary storage doesn't have bound backup storage
                    sql = "select bs from BackupStorageVO bs where bs.type in (:types)";
                    TypedQuery<BackupStorageVO> bq = dbf.getEntityManager().createQuery(sql, BackupStorageVO.class);
                    bq.setParameter("types", hostAllocatorMgr.getBackupStorageTypesByPrimaryStorageTypeFromMetrics(ps.getType()));
                    lst.addAll(bq.getResultList());
                } else if (!bsUuids.isEmpty()) {
                    // the primary storage has bound backup storage, e.g. ceph, fusionstor
                    sql = "select bs from BackupStorageVO bs where bs.uuid in (:uuids)";
                    TypedQuery<BackupStorageVO> bq = dbf.getEntityManager().createQuery(sql, BackupStorageVO.class);
                    bq.setParameter("uuids", bsUuids);
                    lst.addAll(bq.getResultList());
                } else {
                    logger.warn(String.format("the primary storage[uuid:%s, type:%s] needs a bound backup storage," +
                            " but seems it's not added", ps.getUuid(), ps.getType()));
                }
            }

            bss.add(lst);
        }

        List<BackupStorageVO> selectedBss = new ArrayList<>();
        for (List<BackupStorageVO> lst : bss) {
            selectedBss.addAll(lst);
        }

        for (List<BackupStorageVO> l : bss) {
            selectedBss = listIntersection(selectedBss, l);
        }

        if (selectedBss.isEmpty()) {
            reply.setInventories(new ArrayList());
            bus.reply(msg, reply);
            return;
        }

        List<String> bsUuids = selectedBss.stream().map(BackupStorageVO::getUuid).collect(Collectors.toList());
        String sql = "select img" +
                " from ImageVO img, ImageBackupStorageRefVO iref, BackupStorageZoneRefVO zref, BackupStorageVO bs" +
                " where img.uuid = iref.imageUuid" +
                " and iref.backupStorageUuid = zref.backupStorageUuid" +
                " and bs.uuid = zref.backupStorageUuid" +
                " and bs.uuid in (:uuids)" +
                " and zref.zoneUuid = :zoneUuid" +
                " group by img.uuid";
        TypedQuery<ImageVO> iq = dbf.getEntityManager().createQuery(sql, ImageVO.class);
        iq.setParameter("uuids", bsUuids);
        iq.setParameter("zoneUuid", msg.getZoneUuid());
        List<ImageVO> vos = iq.getResultList();
        reply.setInventories(ImageInventory.valueOf(vos));
        bus.reply(msg, reply);
    }

    @Transactional(readOnly = true)
    private void getInterdependentL3NetworksByImageUuid(APIGetInterdependentL3NetworksImagesMsg msg, String accountUuid) {
        APIGetInterdependentL3NetworkImageReply reply = new APIGetInterdependentL3NetworkImageReply();

        String sql = "select bs" +
                " from BackupStorageVO bs, ImageBackupStorageRefVO ref, BackupStorageZoneRefVO zref" +
                " where bs.uuid = ref.backupStorageUuid" +
                " and ref.imageUuid = :imgUuid" +
                " and ref.backupStorageUuid = zref.backupStorageUuid" +
                " and zref.zoneUuid = :zoneUuid";
        TypedQuery<BackupStorageVO> bsq = dbf.getEntityManager().createQuery(sql, BackupStorageVO.class);
        bsq.setParameter("imgUuid", msg.getImageUuid());
        bsq.setParameter("zoneUuid", msg.getZoneUuid());
        List<BackupStorageVO> bss = bsq.getResultList();
        if (bss.isEmpty()) {
            throw new OperationFailureException(argerr("the image[uuid:%s] is not on any backup storage that has been attached to the zone[uuid:%s]",
                            msg.getImageUuid(), msg.getZoneUuid()));
        }

        List<L3NetworkVO> l3s = new ArrayList<>();
        for (BackupStorageVO bs : bss) {
            BackupStorageType bsType = BackupStorageType.valueOf(bs.getType());
            List<String> relatedPrimaryStorageUuids = bsType.findRelatedPrimaryStorage(bs.getUuid());
            if (relatedPrimaryStorageUuids == null) {
                // the backup storage has no strongly-bound primary storage
                List<String> psTypes = hostAllocatorMgr.getPrimaryStorageTypesByBackupStorageTypeFromMetrics(bs.getType());
                sql = "select l3" +
                        " from L3NetworkVO l3, L2NetworkClusterRefVO l2ref," +
                        " PrimaryStorageClusterRefVO psref, PrimaryStorageVO ps" +
                        " where l3.l2NetworkUuid = l2ref.l2NetworkUuid" +
                        " and l2ref.clusterUuid = psref.clusterUuid" +
                        " and psref.primaryStorageUuid = ps.uuid" +
                        " and ps.type in (:psTypes)" +
                        " and ps.zoneUuid = l3.zoneUuid" +
                        " and l3.zoneUuid = :zoneUuid" +
                        " group by l3.uuid";
                TypedQuery<L3NetworkVO> l3q = dbf.getEntityManager().createQuery(sql, L3NetworkVO.class);
                l3q.setParameter("psTypes", psTypes);
                l3q.setParameter("zoneUuid", msg.getZoneUuid());
                l3s.addAll(l3q.getResultList());
            } else if (!relatedPrimaryStorageUuids.isEmpty()) {
                // the backup storage has strongly-bound primary storage, e.g. ceph, fusionstor
                sql = "select l3" +
                        " from L3NetworkVO l3, L2NetworkClusterRefVO l2ref," +
                        " PrimaryStorageClusterRefVO psref, PrimaryStorageVO ps" +
                        " where l3.l2NetworkUuid = l2ref.l2NetworkUuid" +
                        " and l2ref.clusterUuid = psref.clusterUuid" +
                        " and psref.primaryStorageUuid = ps.uuid" +
                        " and ps.uuid in (:psUuids)" +
                        " and ps.zoneUuid = l3.zoneUuid" +
                        " and l3.zoneUuid = :zoneUuid" +
                        " group by l3.uuid";
                TypedQuery<L3NetworkVO> l3q = dbf.getEntityManager().createQuery(sql, L3NetworkVO.class);
                l3q.setParameter("psUuids", relatedPrimaryStorageUuids);
                l3q.setParameter("zoneUuid", msg.getZoneUuid());
                l3s.addAll(l3q.getResultList());
            } else {
                logger.warn(String.format("the backup storage[uuid:%s, type: %s] needs a strongly-bound primary storage," +
                        " but seems the primary storage is not added", bs.getUuid(), bs.getType()));
            }
        }

        List<String> bsUuids = bss.stream().map(BackupStorageVO::getUuid).collect(Collectors.toList());
        for (GetInterdependentL3NetworksExtensionPoint ext : pluginRgty.getExtensionList(GetInterdependentL3NetworksExtensionPoint.class)) {
            l3s = ext.afterFilterByImage(l3s, bsUuids, msg.getImageUuid());
        }

        List<String> l3sFromAccount = acntMgr.getResourceUuidsCanAccessByAccount(accountUuid, L3NetworkVO.class);
        if (l3sFromAccount == null) {
            reply.setInventories(L3NetworkInventory.valueOf(l3s));
        } else {
            reply.setInventories(L3NetworkInventory.valueOf(l3s.stream()
                    .filter(vo -> l3sFromAccount.contains(vo.getUuid()))
                    .collect(Collectors.toList())));
        }
        bus.reply(msg, reply);
    }

    private void handle(APIGetCandidateZonesClustersHostsForCreatingVmMsg msg) {
        DesignatedAllocateHostMsg amsg = new DesignatedAllocateHostMsg();

        ImageVO image = dbf.findByUuid(msg.getImageUuid(), ImageVO.class);
        if (image != null && image.getMediaType() == ImageMediaType.ISO && msg.getRootDiskOfferingUuid() == null) {
            throw new OperationFailureException(argerr("the image[name:%s, uuid:%s] is an ISO, rootDiskOfferingUuid must be set",
                            image.getName(), image.getUuid()));
        }

        amsg.setImage(ImageInventory.valueOf(image));
        amsg.setZoneUuid(msg.getZoneUuid());
        amsg.setClusterUuid(msg.getClusterUuid());

        InstanceOfferingVO insvo = dbf.findByUuid(msg.getInstanceOfferingUuid(), InstanceOfferingVO.class);
        amsg.setCpuCapacity(insvo.getCpuNum());
        amsg.setMemoryCapacity(insvo.getMemorySize());

        long diskSize = 0;
        List<DiskOfferingInventory> diskOfferings = new ArrayList<>();
        if (msg.getDataDiskOfferingUuids() != null) {
            SimpleQuery<DiskOfferingVO> q = dbf.createQuery(DiskOfferingVO.class);
            q.add(DiskOfferingVO_.uuid, Op.IN, msg.getDataDiskOfferingUuids());
            List<DiskOfferingVO> dvos = q.list();
            diskOfferings.addAll(DiskOfferingInventory.valueOf(dvos));
        }

        if (image.getMediaType() == ImageMediaType.ISO) {
            DiskOfferingVO rootDiskOffering = dbf.findByUuid(msg.getRootDiskOfferingUuid(), DiskOfferingVO.class);
            diskOfferings.add(DiskOfferingInventory.valueOf(rootDiskOffering));
        } else {
            diskSize = image.getSize();
        }

        diskSize += diskOfferings.stream().mapToLong(DiskOfferingInventory::getDiskSize).sum();
        amsg.setDiskSize(diskSize);
        amsg.setL3NetworkUuids(msg.getL3NetworkUuids());
        amsg.setVmOperation(VmOperation.NewCreate.toString());
        amsg.setDryRun(true);
        amsg.setListAllHosts(true);
        amsg.setAllocatorStrategy(HostAllocatorConstant.DESIGNATED_HOST_ALLOCATOR_STRATEGY_TYPE);

        if (image.getBackupStorageRefs().size() == 1) {
            amsg.setRequiredBackupStorageUuid(image.getBackupStorageRefs().iterator().next().getBackupStorageUuid());
        } else {
            if (msg.getZoneUuid() == null) {
                throw new OperationFailureException(argerr("zoneUuid must be set because the image[name:%s, uuid:%s] is on multiple backup storage",
                                image.getName(), image.getUuid()));
            }

            ImageBackupStorageSelector selector = new ImageBackupStorageSelector();
            selector.setZoneUuid(msg.getZoneUuid());
            selector.setImageUuid(image.getUuid());
            amsg.setRequiredBackupStorageUuid(selector.select());
        }

        VmInstanceInventory vm = new VmInstanceInventory();
        vm.setUuid(Platform.FAKE_UUID);
        vm.setInstanceOfferingUuid(insvo.getUuid());
        vm.setImageUuid(image.getUuid());
        vm.setCpuNum(insvo.getCpuNum());
        vm.setMemorySize(insvo.getMemorySize());
        vm.setDefaultL3NetworkUuid(msg.getDefaultL3NetworkUuid() == null ? msg.getL3NetworkUuids().get(0) : msg.getDefaultL3NetworkUuid());
        vm.setName("for-getting-candidates-zones-clusters-hosts");
        amsg.setVmInstance(vm);
        if (msg.getSystemTags() != null && !msg.getSystemTags().isEmpty()) {
            amsg.setSystemTags(new ArrayList<String>(msg.getSystemTags()));
        }

        APIGetCandidateZonesClustersHostsForCreatingVmReply areply = new APIGetCandidateZonesClustersHostsForCreatingVmReply();
        bus.makeLocalServiceId(amsg, HostAllocatorConstant.SERVICE_ID);
        bus.send(amsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    areply.setError(reply.getError());
                } else {
                    AllocateHostDryRunReply re = reply.castReply();

                    if (!re.getHosts().isEmpty()) {
                        areply.setHosts(re.getHosts());

                        List<String> clusterUuids = re.getHosts().stream().
                                map(HostInventory::getClusterUuid).collect(Collectors.toList());
                        areply.setClusters(ClusterInventory.valueOf(dbf.listByPrimaryKeys(clusterUuids, ClusterVO.class)));

                        List<String> zoneUuids = re.getHosts().stream().
                                map(HostInventory::getZoneUuid).collect(Collectors.toList());
                        areply.setZones(ZoneInventory.valueOf(dbf.listByPrimaryKeys(zoneUuids, ZoneVO.class)));
                    } else {
                        areply.setHosts(new ArrayList<>());
                        areply.setClusters(new ArrayList<>());
                        areply.setZones(new ArrayList<>());
                    }
                }

                bus.reply(msg, areply);
            }
        });
    }

    private void handle(APIGetCandidatePrimaryStoragesForCreatingVmMsg msg) {
        APIGetCandidatePrimaryStoragesForCreatingVmReply reply = new APIGetCandidatePrimaryStoragesForCreatingVmReply();
        List<AllocatePrimaryStorageMsg> msgs = new ArrayList<>();

        Set<String> psTypes = new HashSet<>();
        List<String> clusterUuids = new ArrayList<>();
        List<DiskOfferingInventory> dataOfferings = new ArrayList<>();
        ImageInventory imageInv = new SQLBatchWithReturn<ImageInventory>(){

            @Override
            protected ImageInventory scripts() {
                List<String> dataOfferingUuids = msg.getDataDiskOfferingUuids() == null ? new ArrayList<>() :
                        msg.getDataDiskOfferingUuids();

                sql("select bs.type from BackupStorageVO bs, ImageBackupStorageRefVO ref" +
                        " where ref.imageUuid =:imageUuid" +
                        " and bs.uuid = ref.backupStorageUuid", String.class)
                        .param("imageUuid", msg.getImageUuid())
                        .list().forEach(it ->
                        psTypes.addAll(hostAllocatorMgr.getPrimaryStorageTypesByBackupStorageTypeFromMetrics((String)it)
                        ));

                clusterUuids.addAll(sql("select distinct ref.clusterUuid" +
                        " from L2NetworkClusterRefVO ref, L3NetworkVO l3" +
                        " where l3.uuid in (:l3Uuids)" +
                        " and ref.l2NetworkUuid = l3.l2NetworkUuid", String.class)
                        .param("l3Uuids", msg.getL3NetworkUuids())
                        .list());

                for (String diskUuid : dataOfferingUuids){
                    dataOfferings.add(DiskOfferingInventory.valueOf(
                            (DiskOfferingVO) q(DiskOfferingVO.class)
                                    .eq(DiskOfferingVO_.uuid, diskUuid)
                                    .find()
                    ));
                }

                ImageVO imageVO = q(ImageVO.class).eq(ImageVO_.uuid, msg.getImageUuid()).find();
                return ImageInventory.valueOf(imageVO);
            }
        }.execute();


        // allocate ps for root volume
        AllocatePrimaryStorageMsg rmsg = new AllocatePrimaryStorageMsg();
        rmsg.setDryRun(true);
        rmsg.setImageUuid(msg.getImageUuid());
        rmsg.setRequiredClusterUuids(clusterUuids);
        if (ImageMediaType.ISO.toString().equals(imageInv.getMediaType())) {
            Tuple t = Q.New(DiskOfferingVO.class).eq(DiskOfferingVO_.uuid, msg.getRootDiskOfferingUuid())
                    .select(DiskOfferingVO_.diskSize, DiskOfferingVO_.allocatorStrategy).findTuple();
            rmsg.setSize((long)t.get(0));
            rmsg.setAllocationStrategy((String)t.get(1));
            rmsg.setDiskOfferingUuid(msg.getRootDiskOfferingUuid());
        } else {
            rmsg.setSize(imageInv.getSize());
        }
        rmsg.setPurpose(PrimaryStorageAllocationPurpose.CreateNewVm.toString());
        rmsg.setPossiblePrimaryStorageTypes(new ArrayList<>(psTypes));
        bus.makeLocalServiceId(rmsg, PrimaryStorageConstant.SERVICE_ID);
        msgs.add(rmsg);

        // allocate ps for data volumes
        for (DiskOfferingInventory dinv : dataOfferings) {
            AllocatePrimaryStorageMsg amsg = new AllocatePrimaryStorageMsg();
            amsg.setDryRun(true);
            amsg.setSize(dinv.getDiskSize());
            amsg.setRequiredClusterUuids(clusterUuids);
            amsg.setAllocationStrategy(dinv.getAllocatorStrategy());
            amsg.setDiskOfferingUuid(dinv.getUuid());
            bus.makeLocalServiceId(amsg, PrimaryStorageConstant.SERVICE_ID);
            msgs.add(amsg);
        }

        new While<>(msgs).all((amsg, completion) ->{
            bus.send(amsg, new CloudBusCallBack(completion) {
                @Override
                public void run(MessageReply r) {
                    if (r.isSuccess()){
                        AllocatePrimaryStorageDryRunReply re = r.castReply();
                        if (amsg.getImageUuid() != null){
                            reply.setRootVolumePrimaryStorages(re.getPrimaryStorageInventories());
                        } else {
                            reply.getDataVolumePrimaryStorages().put(amsg.getDiskOfferingUuid(), re.getPrimaryStorageInventories());
                        }
                    }
                    completion.done();
                }
            });

        }).run(new NoErrorCompletion(msg) {
            @Override
            public void done() {
                bus.reply(msg, reply);
            }
        });
    }

    private void handle(APICreateVmNicMsg msg) {
        final APICreateVmNicEvent evt = new APICreateVmNicEvent(msg.getId());
        VmNicInventory nic = new VmNicInventory();
        VmNicVO nicVO = new VmNicVO();

        FlowChain flowChain = FlowChainBuilder.newSimpleFlowChain();
        flowChain.setName(String.format("create-nic-on-l3-network-%s", msg.getL3NetworkUuid()));
        flowChain.then(new NoRollbackFlow() {
            String __name__ = "create-nic-and-presist-to-db";

            @Override
            public void run(FlowTrigger trigger, Map data) {
                int deviceId = 1;
                String mac = NetworkUtils.generateMacWithDeviceId((short) deviceId);
                nic.setUuid(Platform.getUuid());
                nic.setMac(mac);
                nic.setDeviceId(deviceId);

                nicVO.setUuid(nic.getUuid());
                nicVO.setDeviceId(deviceId);
                nicVO.setMac(mac);
                nicVO.setAccountUuid(msg.getSession().getAccountUuid());

                int tries = 5;
                while (tries-- > 0) {
                    try {
                        new SQLBatch() {
                            @Override
                            protected void scripts() {
                                persist(nicVO);
                            }
                        }.execute();
                        break;
                    } catch (JpaSystemException e) {
                        if (e.getRootCause() instanceof MySQLIntegrityConstraintViolationException &&
                                e.getRootCause().getMessage().contains("Duplicate entry")) {
                            logger.debug(String.format("Concurrent mac allocation. Mac[%s] has been allocated, try allocating another one. " +
                                    "The error[Duplicate entry] printed by jdbc.spi.SqlExceptionHelper is no harm, " +
                                    "we will try finding another mac", nicVO.getMac()));
                            logger.trace("", e);
                            nicVO.setMac(NetworkUtils.generateMacWithDeviceId((short) nicVO.getDeviceId()));
                        } else {
                            throw e;
                        }
                    }
                }

                trigger.next();
            }
        }).then(new Flow() {
            String __name__ = "allocate-nic-ip-and-mac";

            @Override
            public void run(FlowTrigger trigger, Map data) {
                AllocateIpMsg allocateIpMsg = new AllocateIpMsg();
                allocateIpMsg.setL3NetworkUuid(msg.getL3NetworkUuid());
                allocateIpMsg.setRequiredIp(msg.getIp());
                l3nm.updateIpAllocationMsg(allocateIpMsg, nic.getMac());
                bus.makeTargetServiceIdByResourceUuid(allocateIpMsg, L3NetworkConstant.SERVICE_ID, msg.getL3NetworkUuid());

                bus.send(allocateIpMsg, new CloudBusCallBack(trigger) {
                    @Override
                    public void run(MessageReply reply) {
                        if (!reply.isSuccess()) {
                            trigger.fail(reply.getError());
                            return;
                        }

                        AllocateIpReply aReply = reply.castReply();
                        UsedIpInventory ipInventory = aReply.getIpInventory();
                        for (VmNicExtensionPoint ext : pluginRgty.getExtensionList(VmNicExtensionPoint.class)) {
                            ext.afterAddIpAddress(nic.getUuid(), ipInventory.getUuid());
                        }

                        nic.setL3NetworkUuid(aReply.getIpInventory().getL3NetworkUuid());
                        nic.setUsedIpUuid(aReply.getIpInventory().getUuid());
                        trigger.next();
                    }
                });
            }

            @Override
            public void rollback(FlowRollback trigger, Map data) {
                if (nic.getUsedIpUuid() != null) {
                    ReturnIpMsg msg = new ReturnIpMsg();
                    msg.setL3NetworkUuid(nic.getL3NetworkUuid());
                    msg.setUsedIpUuid(nic.getUsedIpUuid());
                    bus.makeTargetServiceIdByResourceUuid(msg, L3NetworkConstant.SERVICE_ID, nic.getL3NetworkUuid());

                    bus.send(msg, new CloudBusCallBack(trigger) {
                        @Override
                        public void run(MessageReply reply) {
                            dbf.removeByPrimaryKey(nic.getUuid(), VmNicVO.class);
                            trigger.rollback();
                        }
                    });
                } else {
                    dbf.removeByPrimaryKey(nic.getUuid(), VmNicVO.class);
                    trigger.rollback();
                }
            }
        });

        flowChain.done(new FlowDoneHandler(msg) {
            @Override
            public void handle(Map data) {
                tagMgr.createTagsFromAPICreateMessage(msg, nic.getUuid(), VmNicVO.class.getSimpleName());
                evt.setInventory(VmNicInventory.valueOf(dbf.reload(nicVO)));
                bus.publish(evt);
            }
        }).error(new FlowErrorHandler(msg) {
            @Override
            public void handle(ErrorCode errCode, Map data) {
                evt.setError(errCode);
                bus.publish(evt);
            }
        }).start();
    }

    private void doCreateVmInstance(final CreateVmInstanceMsg msg, final APICreateMessage cmsg, ReturnValueCompletion<VmInstanceInventory> completion) {
        pluginRgty.getExtensionList(VmInstanceCreateExtensionPoint.class).forEach(extensionPoint -> {
            extensionPoint.preCreateVmInstance(msg);
        });

        final String instanceOfferingUuid = msg.getInstanceOfferingUuid();
        VmInstanceVO vo = new VmInstanceVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setName(msg.getName());
        vo.setClusterUuid(msg.getClusterUuid());
        vo.setDescription(msg.getDescription());
        vo.setImageUuid(msg.getImageUuid());
        vo.setInstanceOfferingUuid(instanceOfferingUuid);
        vo.setState(VmInstanceState.Created);
        vo.setZoneUuid(msg.getZoneUuid());
        vo.setInternalId(dbf.generateSequenceNumber(VmInstanceSequenceNumberVO.class));
        vo.setDefaultL3NetworkUuid(msg.getDefaultL3NetworkUuid());

        SimpleQuery<ImageVO> imgq = dbf.createQuery(ImageVO.class);
        imgq.select(ImageVO_.platform);
        imgq.add(ImageVO_.uuid, Op.EQ, msg.getImageUuid());
        ImagePlatform platform = imgq.findValue();
        vo.setPlatform(platform.toString());

        vo.setCpuNum(msg.getCpuNum());
        vo.setCpuSpeed(msg.getCpuSpeed());
        vo.setMemorySize(msg.getMemorySize());
        vo.setAllocatorStrategy(msg.getAllocatorStrategy());

        String vmType = msg.getType() == null ? VmInstanceConstant.USER_VM_TYPE : msg.getType();
        VmInstanceType type = VmInstanceType.valueOf(vmType);
        VmInstanceFactory factory = getVmInstanceFactory(type);

        VmInstanceVO finalVo = vo;
        vo = new SQLBatchWithReturn<VmInstanceVO>() {
            @Override
            protected VmInstanceVO scripts() {
                finalVo.setAccountUuid(msg.getAccountUuid());
                factory.createVmInstance(finalVo, msg);

                return reload(finalVo);
            }
        }.execute();

        if (cmsg != null) {
            tagMgr.createTagsFromAPICreateMessage(cmsg, vo.getUuid(), VmInstanceVO.class.getSimpleName());
        } else {
            tagMgr.createTags(msg.getSystemTags(), msg.getUserTags(), vo.getUuid(), VmInstanceVO.class.getSimpleName());
        }

        if (instanceOfferingUuid != null) {
            tagMgr.copySystemTag(
                    instanceOfferingUuid,
                    InstanceOfferingVO.class.getSimpleName(),
                    vo.getUuid(),
                    VmInstanceVO.class.getSimpleName(), false);
        }

        if (msg.getImageUuid() != null) {
            tagMgr.copySystemTag(
                    msg.getImageUuid(),
                    ImageVO.class.getSimpleName(),
                    vo.getUuid(),
                    VmInstanceVO.class.getSimpleName(), false);
        }

        if (cmsg != null && cmsg.getSystemTags() != null && !cmsg.getSystemTags().isEmpty()) {
            extEmitter.handleSystemTag(vo.getUuid(), cmsg.getSystemTags());
        } else if (cmsg == null && msg.getSystemTags() != null && !msg.getSystemTags().isEmpty()) {
            extEmitter.handleSystemTag(vo.getUuid(), msg.getSystemTags());
        }

        InstantiateNewCreatedVmInstanceMsg smsg = new InstantiateNewCreatedVmInstanceMsg();
        if (VmCreationStrategy.JustCreate == VmCreationStrategy.valueOf(msg.getStrategy())) {
            VmInstanceInventory inv = VmInstanceInventory.valueOf(vo);
            createVmButNotStart(msg, inv);
            completion.success(inv);
            return;
        }

        smsg.setHostUuid(msg.getHostUuid());
        smsg.setDataDiskOfferingUuids(msg.getDataDiskOfferingUuids());
        smsg.setL3NetworkUuids(msg.getL3NetworkUuids());

        if (msg.getRootDiskOfferingUuid() != null) {
            smsg.setRootDiskOfferingUuid(msg.getRootDiskOfferingUuid());
        } else if (msg.getRootDiskSize() > 0) {
            DiskOfferingVO dvo = new DiskOfferingVO();
            dvo.setUuid(Platform.getUuid());
            dvo.setAccountUuid(msg.getAccountUuid());
            dvo.setDiskSize(msg.getRootDiskSize());
            dvo.setName("for-create-vm-" + vo.getUuid());
            dvo.setType("DefaultDiskOfferingType");
            dvo.setState(DiskOfferingState.Enabled);
            dvo.setAllocatorStrategy(PrimaryStorageConstant.DEFAULT_PRIMARY_STORAGE_ALLOCATION_STRATEGY_TYPE);
            dbf.persist(dvo);
            smsg.setRootDiskOfferingUuid(dvo.getUuid());
        }

        smsg.setVmInstanceInventory(VmInstanceInventory.valueOf(vo));
        smsg.setPrimaryStorageUuidForRootVolume(msg.getPrimaryStorageUuidForRootVolume());
        smsg.setPrimaryStorageUuidForDataVolume(msg.getPrimaryStorageUuidForDataVolume());
        smsg.setStrategy(msg.getStrategy());
        smsg.setTimeout(msg.getTimeout());
        smsg.setRootVolumeSystemTags(msg.getRootVolumeSystemTags());
        smsg.setDataVolumeSystemTags(msg.getDataVolumeSystemTags());
        bus.makeTargetServiceIdByResourceUuid(smsg, VmInstanceConstant.SERVICE_ID, vo.getUuid());
        bus.send(smsg, new CloudBusCallBack(smsg) {
            @Override
            public void run(MessageReply reply) {
                try {
                    if (msg.getRootDiskOfferingUuid() == null && smsg.getRootDiskOfferingUuid() != null) {
                        dbf.removeByPrimaryKey(smsg.getRootDiskOfferingUuid(), DiskOfferingVO.class);
                    }

                    if (reply.isSuccess()) {
                        InstantiateNewCreatedVmInstanceReply r = (InstantiateNewCreatedVmInstanceReply) reply;
                        completion.success(r.getVmInventory());
                    } else {
                        completion.fail(reply.getError());
                    }
                } catch (Exception e) {
                    bus.logExceptionWithMessageDump(msg, e);
                    bus.replyErrorByMessageType(msg, e);
                }
            }
        });
    }

    private void createVmButNotStart(CreateVmInstanceMsg msg, VmInstanceInventory inv) {
        InstantiateVmFromNewCreatedStruct struct = InstantiateVmFromNewCreatedStruct.fromMessage(msg);
        new JsonLabel().create(InstantiateVmFromNewCreatedStruct.makeLabelKey(inv.getUuid()), struct, inv.getUuid());
    }

    private void handle(final CreateVmInstanceMsg msg) {
        if(msg.getZoneUuid() == null){
            String l3Uuid = VmNicSpec.getL3UuidsOfSpec(msg.getL3NetworkUuids()).get(0);
            String zoneUuid = Q.New(L3NetworkVO.class)
                    .select(L3NetworkVO_.zoneUuid)
                    .eq(L3NetworkVO_.uuid, l3Uuid)
                    .findValue();
            msg.setZoneUuid(zoneUuid);
        }

        doCreateVmInstance(msg, null, new ReturnValueCompletion<VmInstanceInventory>(msg) {
            @Override
            public void success(VmInstanceInventory inv) {
                CreateVmInstanceReply reply = new CreateVmInstanceReply();
                reply.setInventory(inv);
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                CreateVmInstanceReply r = new CreateVmInstanceReply();
                r.setError(errorCode);
                bus.reply(msg, r);
            }
        });
    }

    private List<VmNicSpec> getVmNicSpecsFromAPICreateVmInstanceMsg(APICreateVmInstanceMsg msg) {
        List<VmNicSpec> nicSpecs = new ArrayList<>();
        Map<String, List<String>> secondaryNetworksMap = new DualStackNicSecondaryNetworksOperator().getSecondaryNetworksFromSystemTags(msg.getSystemTags());

        for (String l3Uuid : msg.getL3NetworkUuids()) {
            List<L3NetworkInventory> l3Invs = new ArrayList<>();
            L3NetworkInventory inv = L3NetworkInventory.valueOf(dbf.findByUuid(l3Uuid, L3NetworkVO.class));
            l3Invs.add(inv);

            List<String> secondaryNetworksList = secondaryNetworksMap.get(l3Uuid);
            if (secondaryNetworksList == null || secondaryNetworksList.isEmpty()) {
                nicSpecs.add(new VmNicSpec(l3Invs));
                continue;
            }

            for (String secondaryL3Uuid : secondaryNetworksList) {
                if (secondaryL3Uuid.equals(l3Uuid)) {
                    continue;
                }

                L3NetworkInventory secInv = L3NetworkInventory.valueOf(dbf.findByUuid(secondaryL3Uuid, L3NetworkVO.class));
                l3Invs.add(secInv);
            }

            nicSpecs.add(new VmNicSpec(l3Invs));
        }

        return nicSpecs;
    }

    private CreateVmInstanceMsg fromAPICreateVmInstanceMsg(APICreateVmInstanceMsg msg) {
        CreateVmInstanceMsg cmsg = new CreateVmInstanceMsg();

        if(msg.getZoneUuid() != null){
            cmsg.setZoneUuid(msg.getZoneUuid());
        }else{
            String zoneUuid = Q.New(L3NetworkVO.class)
                    .select(L3NetworkVO_.zoneUuid)
                    .eq(L3NetworkVO_.uuid, msg.getL3NetworkUuids().get(0))
                    .findValue();
            cmsg.setZoneUuid(zoneUuid);
        }

        final String instanceOfferingUuid = msg.getInstanceOfferingUuid();
        if (instanceOfferingUuid != null) {
            InstanceOfferingVO iovo = dbf.findByUuid(instanceOfferingUuid, InstanceOfferingVO.class);
            cmsg.setInstanceOfferingUuid(iovo.getUuid());
            cmsg.setCpuSpeed(iovo.getCpuSpeed());
            cmsg.setAllocatorStrategy(iovo.getAllocatorStrategy());
        }

        cmsg.setCpuNum(msg.getCpuNum());
        cmsg.setMemorySize(msg.getMemorySize());

        cmsg.setAccountUuid(msg.getSession().getAccountUuid());
        cmsg.setName(msg.getName());
        cmsg.setImageUuid(msg.getImageUuid());
        cmsg.setL3NetworkUuids(getVmNicSpecsFromAPICreateVmInstanceMsg(msg));
        cmsg.setType(msg.getType());
        cmsg.setRootDiskOfferingUuid(msg.getRootDiskOfferingUuid());
        if (msg.getRootDiskSize() != null) {
            cmsg.setRootDiskSize(msg.getRootDiskSize());
        }
        cmsg.setDataDiskOfferingUuids(msg.getDataDiskOfferingUuids());
        cmsg.setRootVolumeSystemTags(msg.getRootVolumeSystemTags());
        cmsg.setDataVolumeSystemTags(msg.getDataVolumeSystemTags());

        cmsg.setClusterUuid(msg.getClusterUuid());
        cmsg.setHostUuid(msg.getHostUuid());
        cmsg.setPrimaryStorageUuidForRootVolume(msg.getPrimaryStorageUuidForRootVolume());
        cmsg.setPrimaryStorageUuidForDataVolume(getPSUuidForDataVolume(msg.getSystemTags()));
        cmsg.setDescription(msg.getDescription());
        cmsg.setResourceUuid(msg.getResourceUuid());
        cmsg.setDefaultL3NetworkUuid(msg.getDefaultL3NetworkUuid());
        cmsg.setStrategy(msg.getStrategy());
        cmsg.setServiceId(msg.getServiceId());
        cmsg.setHeaders(msg.getHeaders());
        return cmsg;
    }

    private String getPSUuidForDataVolume(List<String> systemTags){
        if(systemTags == null || systemTags.isEmpty()){
            return null;
        }

        return SystemTagUtils.findTagValue(systemTags, VmSystemTags.PRIMARY_STORAGE_UUID_FOR_DATA_VOLUME, VmSystemTags.PRIMARY_STORAGE_UUID_FOR_DATA_VOLUME_TOKEN);
    }

    private void handle(final APICreateVmInstanceMsg msg) {
        doCreateVmInstance(fromAPICreateVmInstanceMsg(msg), msg, new ReturnValueCompletion<VmInstanceInventory>(msg) {
            APICreateVmInstanceEvent evt = new APICreateVmInstanceEvent(msg.getId());

            @Override
            public void success(VmInstanceInventory inv) {
                evt.setInventory(inv);
                bus.publish(evt);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                evt.setError(errorCode);
                bus.publish(evt);
            }
        });
    }

    private void doDeleteVmNic(VmNicInventory nic, Completion completion) {
        thdf.chainSubmit(new ChainTask(completion) {
            @Override
            public String getSyncSignature() {
                return getVmNicSyncSignature(nic.getUuid());
            }

            @Override
            public void run(SyncTaskChain chain) {
                if (nic.getVmInstanceUuid() == null) {
                    List<ReturnIpMsg> msgs = new ArrayList<>();
                    for (UsedIpInventory ip : nic.getUsedIps()) {
                        ReturnIpMsg returnIpMsg = new ReturnIpMsg();
                        returnIpMsg.setUsedIpUuid(ip.getUuid());
                        returnIpMsg.setL3NetworkUuid(ip.getL3NetworkUuid());
                        bus.makeTargetServiceIdByResourceUuid(returnIpMsg, L3NetworkConstant.SERVICE_ID, ip.getL3NetworkUuid());
                        msgs.add(returnIpMsg);
                    }
                    new While<>(msgs).all((msg, com) -> bus.send(msg, new CloudBusCallBack(com) {
                        @Override
                        public void run(MessageReply reply) {
                            if (!reply.isSuccess()) {
                                logger.warn(String.format("failed to return ip address[uuid: %s]", msg.getUsedIpUuid()));
                            }
                            com.done();
                        }
                    })).run(new NopeNoErrorCompletion() {
                        @Override
                        public void done() {
                            dbf.removeByPrimaryKey(nic.getUuid(), VmNicVO.class);
                            completion.success();
                        }
                    });

                    return;
                }

                DetachNicFromVmMsg detachNicFromVmMsg = new DetachNicFromVmMsg();
                detachNicFromVmMsg.setVmInstanceUuid(nic.getVmInstanceUuid());
                detachNicFromVmMsg.setVmNicUuid(nic.getUuid());
                bus.makeTargetServiceIdByResourceUuid(detachNicFromVmMsg, VmInstanceConstant.SERVICE_ID, nic.getVmInstanceUuid());
                bus.send(detachNicFromVmMsg, new CloudBusCallBack(completion) {
                    @Override
                    public void run(MessageReply reply) {
                        if (!reply.isSuccess()) {
                            completion.fail(reply.getError());
                        } else {
                            completion.success();
                        }
                    }
                });
            }

            @Override
            public String getName() {
                return String.format("delete-vmNic-%s", nic.getUuid());
            }
        });
    }

    private void handle(final APIDeleteVmNicMsg msg) {
        APIDeleteVmNicEvent evt = new APIDeleteVmNicEvent(msg.getId());

        VmNicVO nicVO = Q.New(VmNicVO.class).eq(VmNicVO_.uuid, msg.getUuid()).find();
        doDeleteVmNic(VmNicInventory.valueOf(nicVO), new Completion(msg) {
            @Override
            public void success() {
                bus.publish(evt);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                evt.setError(errorCode);
                bus.publish(evt);
            }
        });
    }

    private String getVmNicSyncSignature(String nicUuid) {
        return String.format("vmNic-%s", nicUuid);
    }

    private void doAttachL3ToNic(final VmNicInventory nic, final String l3Uuid, boolean attachedToHypervisor, final Completion completion) {
        thdf.chainSubmit(new ChainTask(completion) {
            @Override
            public String getSyncSignature() {
                return getVmNicSyncSignature(nic.getUuid());
            }

            @Override
            public void run(SyncTaskChain chain) {
                VmInstanceVO vmVo = null;

                L3NetworkVO l3Vo = Q.New(L3NetworkVO.class).eq(L3NetworkVO_.uuid, l3Uuid).find();
                FlowChain flowChain = FlowChainBuilder.newSimpleFlowChain();
                flowChain.setName(String.format("attach-l3-network-%s-to-nic-%s", l3Uuid, nic.getUuid()));
                flowChain.getData().put(VmInstanceConstant.Params.VmNicInventory.toString(), nic);
                flowChain.getData().put(VmInstanceConstant.Params.L3NetworkInventory.toString(), L3NetworkInventory.valueOf(l3Vo));
                if (nic.getVmInstanceUuid() != null) {
                    vmVo = dbf.findByUuid(nic.getVmInstanceUuid(), VmInstanceVO.class);
                    flowChain.getData().put(VmInstanceConstant.Params.vmInventory.toString(), VmInstanceInventory.valueOf(vmVo));
                }
                flowChain.then(new VmPreAllocateNicIpFlow());
                flowChain.then(new VmNicAllocateIpFlow());
                if (vmVo != null) {
                    flowChain.then(new VmChangeDefaultL3NetworkFlow());
                }
                /* when doAttachL3ToNic is called from APIMsg, it need to update nic to hypervisor,
                * or the the outer caller will update nic to hypervisor */
                if (attachedToHypervisor) {
                    flowChain.then(new AddL3NetworkToVmNicFlow());
                }
                flowChain.done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        completion.success();
                        chain.next();
                    }
                }).error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                        chain.next();
                    }
                }).start();
            }

            @Override
            public String getName() {
                return String.format("attach-l3-network-%s-to-nic-%s", l3Uuid, nic.getUuid());
            }
        });
    }

    private void handle(final AttachL3NetworkToVmNicMsg msg) {
        final AttachL3NetworkToVmNicReply reply = new AttachL3NetworkToVmNicReply();
        VmNicVO vmNicVO = Q.New(VmNicVO.class).eq(VmNicVO_.uuid, msg.getVmNicUuid()).find();

        doAttachL3ToNic(VmNicInventory.valueOf(vmNicVO), msg.getL3NetworkUuid(), false, new Completion(msg) {
            @Override
            public void success() {
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    private void handle(final APIAttachL3NetworkToVmNicMsg msg) {
        final APIAttachL3NetworkToVmNicEvent evt = new APIAttachL3NetworkToVmNicEvent(msg.getId());
        VmNicVO vmNicVO = Q.New(VmNicVO.class).eq(VmNicVO_.uuid, msg.getVmNicUuid()).find();

        if (msg.getStaticIp() != null) {
            new StaticIpOperator().setStaticIp(vmNicVO.getVmInstanceUuid(), msg.getL3NetworkUuid(), msg.getStaticIp());
        }
        doAttachL3ToNic(VmNicInventory.valueOf(vmNicVO), msg.getL3NetworkUuid(), true, new Completion(msg) {
            @Override
            public void success() {
                VmNicVO vmNicVO = Q.New(VmNicVO.class).eq(VmNicVO_.uuid, msg.getVmNicUuid()).find();
                evt.setInventory(VmNicInventory.valueOf(vmNicVO));
                bus.publish(evt);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                new StaticIpOperator().deleteStaticIpByVmUuidAndL3Uuid(vmNicVO.getVmInstanceUuid(), msg.getL3NetworkUuid());
                evt.setError(errorCode);
                bus.publish(evt);
            }
        });
    }

    private void doDetachIpAddressFromNic(String vmNicUuid, String usedIpUuid, final Completion completion) {
        thdf.chainSubmit(new ChainTask(completion) {
            @Override
            public String getSyncSignature() {
                return getVmNicSyncSignature(vmNicUuid);
            }

            @Override
            public void run(SyncTaskChain chain) {
                /* detach last ip, will delete the nic */
                VmNicInventory nic = VmNicInventory.valueOf(dbf.findByUuid(vmNicUuid, VmNicVO.class));
                UsedIpInventory usedIp = UsedIpInventory.valueOf(dbf.findByUuid(usedIpUuid, UsedIpVO.class));

                if (nic.getUsedIps().size() <= 1) {
                    doDeleteVmNic(nic, completion);
                    chain.next();
                    return;
                }

                VmInstanceVO vmVo = null;
                if (nic.getVmInstanceUuid() != null) {
                    vmVo = dbf.findByUuid(nic.getVmInstanceUuid(), VmInstanceVO.class);
                }

                FlowChain flowChain = FlowChainBuilder.newSimpleFlowChain();
                flowChain.setName(String.format("detach-ip-%s-from-nic-%s", usedIp.getIp(), nic.getUuid()));
                flowChain.getData().put(VmInstanceConstant.Params.VmNicInventory.toString(), nic);
                flowChain.getData().put(VmInstanceConstant.Params.UsedIPInventory.toString(), usedIp);
                if (vmVo != null) {
                    flowChain.getData().put(VmInstanceConstant.Params.vmInventory.toString(), VmInstanceInventory.valueOf(vmVo));
                    flowChain.then(new DeleteL3NetworkFromVmNicFlow());
                }
                flowChain.then(new VmNicReturnIpFlow());
                if (vmVo != null) {
                    flowChain.then(new VmChangeDefaultL3NetworkFlow());
                }
                flowChain.done(new FlowDoneHandler(completion) {
                    @Override
                    public void handle(Map data) {
                        completion.success();
                        chain.next();
                    }
                }).error(new FlowErrorHandler(completion) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        completion.fail(errCode);
                        chain.next();
                    }
                }).start();
            }

            @Override
            public String getName() {
                return String.format("detach-usedIp-%s-to-nic-%s", vmNicUuid, usedIpUuid);
            }
        });
    }

    private void handle(final DetachIpAddressFromVmNicMsg msg) {
        final DetachIpAddressFromVmNicReply reply = new DetachIpAddressFromVmNicReply();

        doDetachIpAddressFromNic(msg.getVmNicUuid(), msg.getUsedIpUuid(), new Completion(msg) {
            @Override
            public void success() {
                bus.reply(msg, reply);
            }

            @Override
            public void fail(ErrorCode errorCode) {
                reply.setError(errorCode);
                bus.reply(msg, reply);
            }
        });
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(VmInstanceConstant.SERVICE_ID);
    }

    private void createVmFlowChainBuilder() throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        createVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(createVmWorkFlowElements).construct();
        stopVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(stopVmWorkFlowElements).construct();
        rebootVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(rebootVmWorkFlowElements).construct();
        startVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(startVmWorkFlowElements).construct();
        destroyVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(destroyVmWorkFlowElements).construct();
        migrateVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(migrateVmWorkFlowElements).construct();
        attachVolumeFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(attachVolumeWorkFlowElements).construct();
        attachIsoFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(attachIsoWorkFlowElements).construct();
        detachIsoFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(detachIsoWorkFlowElements).construct();
        expungeVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(expungeVmWorkFlowElements).construct();
        pauseVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(pauseVmWorkFlowElements).construct();
        resumeVmFlowBuilder = FlowChainBuilder.newBuilder().setFlowClassNames(resumeVmWorkFlowElements).construct();
    }

    private void populateExtensions() {
        for (VmInstanceFactory ext : pluginRgty.getExtensionList(VmInstanceFactory.class)) {
            VmInstanceFactory old = vmInstanceFactories.get(ext.getType().toString());
            if (old != null) {
                throw new CloudRuntimeException(String.format("duplicate VmInstanceFactory[%s, %s] for type[%s]",
                        old.getClass().getName(), ext.getClass().getName(), ext.getType()));
            }
            vmInstanceFactories.put(ext.getType().toString(), ext);
        }

        for (VmInstanceBaseExtensionFactory ext : pluginRgty.getExtensionList(VmInstanceBaseExtensionFactory.class)) {
            for (Class clz : ext.getMessageClasses()) {
                VmInstanceBaseExtensionFactory old = vmInstanceBaseExtensionFactories.get(clz);
                if (old != null) {
                    throw new CloudRuntimeException(String.format("duplicate VmInstanceBaseExtensionFactory[%s, %s] for the" +
                            " message[%s]", old.getClass(), ext.getClass(), clz));
                }

                vmInstanceBaseExtensionFactories.put(clz, ext);
            }
        }
    }

    @Override
    public boolean start() {
        try {
            createVmFlowChainBuilder();
            populateExtensions();
            installSystemTagValidator();
            installGlobalConfigUpdater();

            bus.installBeforeDeliveryMessageInterceptor(new AbstractBeforeDeliveryMessageInterceptor() {
                @Override
                public void beforeDeliveryMessage(Message msg) {
                    if (msg instanceof NeedQuotaCheckMessage) {
                        if (((NeedQuotaCheckMessage) msg).getAccountUuid() == null ||
                                ((NeedQuotaCheckMessage) msg).getAccountUuid().equals("")) {
                            // skip admin scheduler
                            return;
                        }
                        List<Quota> quotas = acntMgr.getMessageQuotaMap().get(msg.getClass());
                        if (quotas == null || quotas.size() == 0) {
                            return;
                        }
                        Map<String, QuotaPair> pairs = new QuotaUtil().
                                makeQuotaPairs(((NeedQuotaCheckMessage) msg).getAccountUuid());
                        for (Quota quota : quotas) {
                            quota.getOperator().checkQuota((NeedQuotaCheckMessage) msg, pairs);
                        }
                    }
                }
            }, StartVmInstanceMsg.class);
            return true;
        } catch (Exception e) {
            throw new CloudConfigureFailException(VmInstanceManagerImpl.class, e.getMessage(), e);
        }
    }

    private void installGlobalConfigUpdater() {
        VmGlobalConfig.VM_EXPUNGE_INTERVAL.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startVmExpungeTask();
            }
        });
        VmGlobalConfig.VM_EXPUNGE_PERIOD.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startVmExpungeTask();
            }
        });
        VmGlobalConfig.VM_DELETION_POLICY.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startVmExpungeTask();
            }
        });
    }

    private void installHostnameValidator() {
        class HostNameValidator implements SystemTagCreateMessageValidator, SystemTagValidator {
            private void validateHostname(String tag, String hostname) {
                DomainValidator domainValidator = DomainValidator.getInstance(true);
                if (!domainValidator.isValid(hostname)) {
                    throw new ApiMessageInterceptionException(argerr("hostname[%s] specified in system tag[%s] is not a valid domain name", hostname, tag));
                }
            }

            @Override
            public void validateSystemTagInCreateMessage(APICreateMessage cmsg) {
                final NewVmInstanceMessage msg = (NewVmInstanceMessage) cmsg;

                int hostnameCount = 0;
                for (String sysTag : msg.getSystemTags()) {
                    if (VmSystemTags.HOSTNAME.isMatch(sysTag)) {
                        if (++hostnameCount > 1) {
                            throw new ApiMessageInterceptionException(argerr("only one hostname system tag is allowed, but %s got", hostnameCount));
                        }

                        String hostname = VmSystemTags.HOSTNAME.getTokenByTag(sysTag, VmSystemTags.HOSTNAME_TOKEN);

                        validateHostname(sysTag, hostname);
                        List<String> l3NetworkUuids = msg.getL3NetworkUuids();
                        l3NetworkUuids.forEach(it->validateHostNameOnDefaultL3Network(sysTag, hostname, it));
                    } else if (VmSystemTags.STATIC_IP.isMatch(sysTag)) {
                        validateStaticIp(sysTag);
                    } else if (VmSystemTags.DUAL_STACK_NIC.isMatch(sysTag)) {
                        validateDualStackNic(sysTag);
                    }
                }

                validateDualStackNic(msg.getSystemTags(), msg.getL3NetworkUuids());
            }

            private void validateStaticIp(String sysTag) {
                Map<String, String> token = TagUtils.parse(VmSystemTags.STATIC_IP.getTagFormat(), sysTag);
                String l3Uuid = token.get(VmSystemTags.STATIC_IP_L3_UUID_TOKEN);
                if (!dbf.isExist(l3Uuid, L3NetworkVO.class)) {
                    throw new ApiMessageInterceptionException(argerr("L3 network[uuid:%s] not found. Please correct your system tag[%s] of static IP",
                            l3Uuid, sysTag));
                }

                String ip = token.get(VmSystemTags.STATIC_IP_TOKEN);
                L3NetworkVO l3NetworkVO = dbf.findByUuid(l3Uuid, L3NetworkVO.class);
                if (l3NetworkVO.getIpVersion() == IPv6Constants.IPv4) {
                    if (!NetworkUtils.isIpv4Address(ip)) {
                        throw new ApiMessageInterceptionException(argerr("%s is not a valid IPv4 address. Please correct your system tag[%s] of static IP",
                                ip, sysTag));
                    }
                } else {
                    ip = IPv6NetworkUtils.ipv6TagValueToAddress(ip);
                    if (!IPv6NetworkUtils.isIpv6Address(ip)) {
                        throw new ApiMessageInterceptionException(argerr("%s is not a valid IPv6 address. Please correct your system tag[%s] of static IP",
                                ip, sysTag));
                    }
                }

                CheckIpAvailabilityMsg cmsg = new CheckIpAvailabilityMsg();
                cmsg.setIp(ip);
                cmsg.setL3NetworkUuid(l3Uuid);
                bus.makeLocalServiceId(cmsg, L3NetworkConstant.SERVICE_ID);
                MessageReply r = bus.call(cmsg);
                if (!r.isSuccess()) {
                    throw new ApiMessageInterceptionException(inerr(r.getError().getDetails()));
                }

                CheckIpAvailabilityReply cr = r.castReply();
                if (!cr.isAvailable()) {
                    throw new ApiMessageInterceptionException(operr("IP[%s] is not available on the L3 network[uuid:%s] because: %s", ip, l3Uuid, cr.getReason()));
                }
            }

            private void validateDualStackNic(List<String> systemTags, List<String> l3Uuids) {
                Map<String, List<String>> secondaryNetworksMap = new DualStackNicSecondaryNetworksOperator().getSecondaryNetworksFromSystemTags(systemTags);
                Set<String> l3NetworkSet = new HashSet<>();
                for (Map.Entry<String, List<String>> e : secondaryNetworksMap.entrySet()) {
                    int ipv4Count = 0;
                    int statefulIpv6Count = 0;
                    String primaryL3 = e.getKey();
                    List<String> secondaryNetworksList = e.getValue();

                    for (String uuid : secondaryNetworksList) {
                        if (!l3NetworkSet.contains(uuid)) {
                            l3NetworkSet.add(uuid);
                        } else {
                            throw new ApiMessageInterceptionException(argerr("l3 network [uuid: %s] is added to vm more than once", uuid));
                        }

                        L3NetworkVO l3Vo = dbf.findByUuid(uuid, L3NetworkVO.class);
                        if (l3Vo.getIpVersion() == IPv6Constants.IPv4) {
                            ipv4Count++;
                        } else {
                            L3NetworkInventory l3Inv = L3NetworkInventory.valueOf(l3Vo);
                            if (!l3Inv.getIpRanges().get(0).getAddressMode().equals(IPv6Constants.SLAAC)) {
                                statefulIpv6Count++;
                            }
                        }
                    }
                    l3NetworkSet.remove(primaryL3);

                    if (ipv4Count > 1) {
                        throw new ApiMessageInterceptionException(argerr("there are %d ipv4 network on same nic", ipv4Count));
                    }

                    if (statefulIpv6Count > 1) {
                        throw new ApiMessageInterceptionException(argerr("there are %d ipv6 stateful network on same nic", statefulIpv6Count));
                    }
                }

                for (String uuid : l3Uuids) {
                    if (l3NetworkSet.contains(uuid)) {
                        throw new ApiMessageInterceptionException(argerr("l3 network [uuid: %s] is added to vm more than once", uuid));
                    }
                }
            }

            private void validateDualStackNic(String sysTag) {
                Map<String, String> token = TagUtils.parse(VmSystemTags.DUAL_STACK_NIC.getTagFormat(), sysTag);
                String primaryL3Uuid = token.get(VmSystemTags.DUAL_STACK_NIC_PRIMARY_L3_TOKEN);
                L3NetworkVO primaryL3Vo = dbf.findByUuid(primaryL3Uuid, L3NetworkVO.class);
                if (primaryL3Vo == null) {
                    throw new ApiMessageInterceptionException(argerr("L3 network[uuid:%s] not found. Please correct your system tag[%s] of dualStackNic",
                            primaryL3Uuid, sysTag));
                }

                String secondaryL3Uuid = token.get(VmSystemTags.DUAL_STACK_NIC_SECONDARY_L3_TOKEN);
                L3NetworkVO secondaryL3Vo = dbf.findByUuid(secondaryL3Uuid, L3NetworkVO.class);
                if (secondaryL3Vo == null) {
                    throw new ApiMessageInterceptionException(argerr("L3 network[uuid:%s] not found. Please correct your system tag[%s] of dualStackNic",
                            primaryL3Uuid, sysTag));
                }

                if (!primaryL3Vo.getL2NetworkUuid().equals(secondaryL3Vo.getL2NetworkUuid())) {
                    throw new ApiMessageInterceptionException(operr("L3 networks[primaryL3Uuid:%s, secondaryL3Uuid:%s] of dualStackNic is not on same l2 network",
                            primaryL3Uuid, secondaryL3Uuid));
                }

                L3NetworkInventory primaryL3 = L3NetworkInventory.valueOf(primaryL3Vo);
                L3NetworkInventory secodaryL3 = L3NetworkInventory.valueOf(secondaryL3Vo);
                if (primaryL3.getIpRanges().isEmpty()) {
                    throw new ApiMessageInterceptionException(operr("L3 networks[uuid:%s] does not have ip range", primaryL3Uuid));
                }

                if (secodaryL3.getIpRanges().isEmpty()) {
                    throw new ApiMessageInterceptionException(operr("L3 networks[uuid:%s] does not have ip range", secondaryL3Uuid));
                }
            }

            @Transactional(readOnly = true)
            private List<SystemTagVO> querySystemTagsByL3(String tag, String l3Uuid) {
                String sql = "select t" +
                        " from SystemTagVO t, VmInstanceVO vm, VmNicVO nic" +
                        " where t.resourceUuid = vm.uuid" +
                        " and vm.uuid = nic.vmInstanceUuid" +
                        " and nic.l3NetworkUuid = :l3Uuid" +
                        " and t.tag = :sysTag";
                TypedQuery<SystemTagVO> q = dbf.getEntityManager().createQuery(sql, SystemTagVO.class);
                q.setParameter("l3Uuid", l3Uuid);
                q.setParameter("sysTag", tag);
                return q.getResultList();
            }

            private void validateHostNameOnDefaultL3Network(String tag, String hostname, String l3Uuid) {
                List<SystemTagVO> vos = querySystemTagsByL3(tag, l3Uuid);

                if (!vos.isEmpty()) {
                    SystemTagVO sameTag = vos.get(0);
                    throw new ApiMessageInterceptionException(argerr("conflict hostname in system tag[%s];" +
                                    " there has been a VM[uuid:%s] having hostname[%s] on L3 network[uuid:%s]",
                            tag, sameTag.getResourceUuid(), hostname, l3Uuid));
                }
            }

            @Override
            public void validateSystemTag(String resourceUuid, Class resourceType, String systemTag) {
                if (VmSystemTags.HOSTNAME.isMatch(systemTag)) {
                    String hostname = VmSystemTags.HOSTNAME.getTokenByTag(systemTag, VmSystemTags.HOSTNAME_TOKEN);
                    validateHostname(systemTag, hostname);

                    SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
                    q.select(VmInstanceVO_.defaultL3NetworkUuid);
                    q.add(VmInstanceVO_.uuid, Op.EQ, resourceUuid);
                    String defaultL3Uuid = q.findValue();

                    validateHostNameOnDefaultL3Network(systemTag, hostname, defaultL3Uuid);
                } else if (VmSystemTags.STATIC_IP.isMatch(systemTag)) {
                    validateStaticIp(systemTag);
                } else if (VmSystemTags.BOOT_ORDER.isMatch(systemTag)) {
                    validateBootOrder(systemTag);
                }  else if (VmSystemTags.DUAL_STACK_NIC.isMatch(systemTag)) {
                    validateDualStackNic(systemTag);
                }
            }

            private void validateBootOrder(String systemTag) {
                String order = VmSystemTags.BOOT_ORDER.getTokenByTag(systemTag, VmSystemTags.BOOT_ORDER_TOKEN);
                for (String o : order.split(",")) {
                    try {
                        VmBootDevice.valueOf(o);
                    } catch (IllegalArgumentException e) {
                        throw new OperationFailureException(argerr("invalid boot device[%s] in boot order[%s]", o, order));
                    }
                }
            }
        }

        HostNameValidator hostnameValidator = new HostNameValidator();
        tagMgr.installCreateMessageValidator(VmInstanceVO.class.getSimpleName(), hostnameValidator);
        VmSystemTags.HOSTNAME.installValidator(hostnameValidator);

    }

    private void installUserdataValidator() {
        class UserDataValidator implements SystemTagCreateMessageValidator, SystemTagValidator {

            private void check(String resourceUuid, Class resourceType) {
                int existUserdataTagCount = VmSystemTags.USERDATA.getTags(resourceUuid, resourceType).size();
                if (existUserdataTagCount > 0) {
                    throw new OperationFailureException(argerr(
                            "Already have one userdata systemTag for vm[uuid: %s].",
                            resourceUuid));
                }
            }

            @Override
            public void validateSystemTag(String resourceUuid, Class resourceType, String systemTag) {
                if (!VmSystemTags.USERDATA.isMatch(systemTag)) {
                    return;
                }
                check(resourceUuid, resourceType);
            }

            @Override
            public void validateSystemTagInCreateMessage(APICreateMessage msg) {
                int userdataTagCount = 0;
                for (String sysTag : msg.getSystemTags()) {
                    if (VmSystemTags.USERDATA.isMatch(sysTag)) {
                        if (userdataTagCount > 0) {
                            throw new OperationFailureException(argerr(
                                    "Shouldn't be more than one userdata systemTag for one vm."));
                        }
                        userdataTagCount++;

                        check(msg.getResourceUuid(), VmInstanceVO.class);
                    }
                }
            }
        }

        UserDataValidator userDataValidator = new UserDataValidator();
        tagMgr.installCreateMessageValidator(VmInstanceVO.class.getSimpleName(), userDataValidator);
        VmSystemTags.USERDATA.installValidator(userDataValidator);
    }

    private void installBootModeValidator() {
        class BootModeValidator implements SystemTagCreateMessageValidator, SystemTagValidator {
            @Override
            public void validateSystemTag(String resourceUuid, Class resourceType, String systemTag) {
                if (!VmSystemTags.BOOT_MODE.isMatch(systemTag)) {
                    return;
                }

                String bootMode = VmSystemTags.BOOT_MODE.getTokenByTag(systemTag, VmSystemTags.BOOT_MODE_TOKEN);
                validateBootMode(systemTag, bootMode);
            }

            @Override
            public void validateSystemTagInCreateMessage(APICreateMessage msg) {
                if (msg.getSystemTags() == null || msg.getSystemTags().isEmpty()) {
                    return;
                }

                int bootModeCount = 0;
                for (String systemTag : msg.getSystemTags()) {
                    if (VmSystemTags.BOOT_MODE.isMatch(systemTag)) {
                        if (++bootModeCount > 1) {
                            throw new ApiMessageInterceptionException(argerr("only one bootMode system tag is allowed, but %d got", bootModeCount));
                        }

                        String bootMode = VmSystemTags.BOOT_MODE.getTokenByTag(systemTag, VmSystemTags.BOOT_MODE_TOKEN);
                        validateBootMode(systemTag, bootMode);
                    }
                }
            }

            private void validateBootMode(String systemTag, String bootMode) {
                boolean valid = false;
                for (ImageBootMode bm : ImageBootMode.values()) {
                    if (bm.name().equalsIgnoreCase(bootMode)) {
                        valid = true;
                        break;
                    }
                }
                if (!valid) {
                    throw new ApiMessageInterceptionException(argerr(
                            "[%s] specified in system tag [%s] is not a valid boot mode", bootMode, systemTag)
                    );
                }
            }
        }

        BootModeValidator validator = new BootModeValidator();
        tagMgr.installCreateMessageValidator(VmInstanceVO.class.getSimpleName(), validator);
        VmSystemTags.BOOT_MODE.installValidator(validator);
    }

    private void installCleanTrafficValidator() {
        class CleanTrafficValidator implements SystemTagCreateMessageValidator, SystemTagValidator {
            @Override
            public void validateSystemTagInCreateMessage(APICreateMessage msg) {
                if (msg instanceof APICreateVmInstanceMsg) {
                    Optional.ofNullable(msg.getSystemTags()).ifPresent(it -> {
                        if (it.stream().anyMatch(tag -> VmSystemTags.CLEAN_TRAFFIC.isMatch(tag))) {
                            validateVmType(null, ((APICreateVmInstanceMsg) msg).getType());
                        }
                    });
                }
            }

            @Override
            public void validateSystemTag(String resourceUuid, Class resourceType, String systemTag) {
                validateVmType(resourceUuid, null);
            }

            private void validateVmType(String vmUuid, String vmType) {
                if (vmType == null) {
                    vmType = Q.New(VmInstanceVO.class).eq(VmInstanceVO_.uuid, vmUuid).select(VmInstanceVO_.type).findValue();
                }

                if (!VmInstanceConstant.USER_VM_TYPE.equals(vmType)) {
                    throw new ApiMessageInterceptionException(argerr(
                            "clean traffic is not supported for vm type [%s]", vmType)
                    );
                }
            }
        }

        CleanTrafficValidator validator = new CleanTrafficValidator();
        tagMgr.installCreateMessageValidator(VmInstanceVO.class.getSimpleName(), validator);
        VmSystemTags.CLEAN_TRAFFIC.installValidator(validator);
    }

    private void installMachineTypeValidator() {
        class MachineTypeValidator implements SystemTagCreateMessageValidator, SystemTagValidator {
            @Override
            public void validateSystemTagInCreateMessage(APICreateMessage msg) {
                Optional.ofNullable(msg.getSystemTags()).ifPresent(it -> it.forEach(this::validateMachineType));
            }

            @Override
            public void validateSystemTag(String resourceUuid, Class resourceType, String systemTag) {
                validateMachineType(systemTag);
            }

            private void validateMachineType(String systemTag) {
                if (!VmSystemTags.MACHINE_TYPE.isMatch(systemTag)) {
                    return;
                }

                String type = VmSystemTags.MACHINE_TYPE.getTokenByTag(systemTag, VmSystemTags.MACHINE_TYPE_TOKEN);
                if (!"q35".equals(type) && !"pc".equals(type)) {
                    throw new ApiMessageInterceptionException(argerr(
                            "vm machine type requires [q35, pc], but get [%s]", type)
                    );
                }
            }
        }

        MachineTypeValidator validator = new MachineTypeValidator();
        tagMgr.installCreateMessageValidator(VmInstanceVO.class.getSimpleName(), validator);
        VmSystemTags.MACHINE_TYPE.installValidator(validator);
    }

    private void installSystemTagValidator() {
        installHostnameValidator();
        installUserdataValidator();
        installBootModeValidator();
        installCleanTrafficValidator();
        installMachineTypeValidator();
    }

    @Override
    public boolean stop() {
        return true;
    }

    @Override
    public VmInstanceFactory getVmInstanceFactory(VmInstanceType type) {
        VmInstanceFactory factory = vmInstanceFactories.get(type.toString());
        if (factory == null) {
            throw new CloudRuntimeException(String.format("No VmInstanceFactory of type[%s] found", type));
        }
        return factory;
    }

    @Override
    public VmInstanceBaseExtensionFactory getVmInstanceBaseExtensionFactory(Message msg) {
        return vmInstanceBaseExtensionFactories.get(msg.getClass());
    }



    @Override
    public FlowChain getCreateVmWorkFlowChain(VmInstanceInventory inv) {
        return createVmFlowBuilder.build();
    }

    @Override
    public FlowChain getStopVmWorkFlowChain(VmInstanceInventory inv) {
        return stopVmFlowBuilder.build();
    }

    @Override
    public FlowChain getRebootVmWorkFlowChain(VmInstanceInventory inv) {
        return rebootVmFlowBuilder.build();
    }

    @Override
    public FlowChain getStartVmWorkFlowChain(VmInstanceInventory inv) {
        return startVmFlowBuilder.build();
    }

    @Override
    public FlowChain getDestroyVmWorkFlowChain(VmInstanceInventory inv) {
        return destroyVmFlowBuilder.build();
    }

    @Override
    public FlowChain getMigrateVmWorkFlowChain(VmInstanceInventory inv) {
        return migrateVmFlowBuilder.build();
    }

    @Override
    public FlowChain getAttachUninstantiatedVolumeWorkFlowChain(VmInstanceInventory inv) {
        return attachVolumeFlowBuilder.build();
    }


    @Override
    public FlowChain getAttachIsoWorkFlowChain(VmInstanceInventory inv) {
        return attachIsoFlowBuilder.build();
    }

    @Override
    public FlowChain getDetachIsoWorkFlowChain(VmInstanceInventory inv) {
        return detachIsoFlowBuilder.build();
    }

    @Override
    public FlowChain getExpungeVmWorkFlowChain(VmInstanceInventory inv) {
        return expungeVmFlowBuilder.build();
    }

    public FlowChain getPauseWorkFlowChain(VmInstanceInventory inv) {
        return pauseVmFlowBuilder.build();
    }

    public FlowChain getResumeVmWorkFlowChain(VmInstanceInventory inv) {
        return resumeVmFlowBuilder.build();
    }

    public void setCreateVmWorkFlowElements(List<String> createVmWorkFlowElements) {
        this.createVmWorkFlowElements = createVmWorkFlowElements;
    }

    public void setStopVmWorkFlowElements(List<String> stopVmWorkFlowElements) {
        this.stopVmWorkFlowElements = stopVmWorkFlowElements;
    }

    public void setRebootVmWorkFlowElements(List<String> rebootVmWorkFlowElements) {
        this.rebootVmWorkFlowElements = rebootVmWorkFlowElements;
    }

    public void setStartVmWorkFlowElements(List<String> startVmWorkFlowElements) {
        this.startVmWorkFlowElements = startVmWorkFlowElements;
    }

    public void setDestroyVmWorkFlowElements(List<String> destroyVmWorkFlowElements) {
        this.destroyVmWorkFlowElements = destroyVmWorkFlowElements;
    }

    public void setMigrateVmWorkFlowElements(List<String> migrateVmWorkFlowElements) {
        this.migrateVmWorkFlowElements = migrateVmWorkFlowElements;
    }

    public void setAttachVolumeWorkFlowElements(List<String> attachVolumeWorkFlowElements) {
        this.attachVolumeWorkFlowElements = attachVolumeWorkFlowElements;
    }

    public void setAttachIsoWorkFlowElements(List<String> attachIsoWorkFlowElements) {
        this.attachIsoWorkFlowElements = attachIsoWorkFlowElements;
    }

    public void setDetachIsoWorkFlowElements(List<String> detachIsoWorkFlowElements) {
        this.detachIsoWorkFlowElements = detachIsoWorkFlowElements;
    }

    public void setExpungeVmWorkFlowElements(List<String> expungeVmWorkFlowElements) {
        this.expungeVmWorkFlowElements = expungeVmWorkFlowElements;
    }

    public void setPauseVmWorkFlowElements(List<String> pauseVmWorkFlowElements) {
        this.pauseVmWorkFlowElements = pauseVmWorkFlowElements;
    }

    public void setResumeVmWorkFlowElements(List<String> resumeVmWorkFlowElements) {
        this.resumeVmWorkFlowElements = resumeVmWorkFlowElements;
    }

    @Override
    public List<Quota> reportQuota() {
        QuotaOperator checker = new VmQuotaOperator() ;

        Quota quota = new Quota();
        QuotaPair p;

        p = new QuotaPair();
        p.setName(VmQuotaConstant.VM_TOTAL_NUM);
        p.setValue(VmQuotaGlobalConfig.VM_TOTAL_NUM.defaultValue(Long.class));
        quota.addPair(p);

        p = new QuotaPair();
        p.setName(VmQuotaConstant.VM_RUNNING_NUM);
        p.setValue(VmQuotaGlobalConfig.VM_RUNNING_NUM.defaultValue(Long.class));
        quota.addPair(p);

        p = new QuotaPair();
        p.setName(VmQuotaConstant.VM_RUNNING_CPU_NUM);
        p.setValue(VmQuotaGlobalConfig.VM_RUNNING_CPU_NUM.defaultValue(Long.class));
        quota.addPair(p);

        p = new QuotaPair();
        p.setName(VmQuotaConstant.VM_RUNNING_MEMORY_SIZE);
        p.setValue(VmQuotaGlobalConfig.VM_RUNNING_MEMORY_SIZE.defaultValue(Long.class));
        quota.addPair(p);

        p = new QuotaPair();
        p.setName(VmQuotaConstant.DATA_VOLUME_NUM);
        p.setValue(VmQuotaGlobalConfig.DATA_VOLUME_NUM.defaultValue(Long.class));
        quota.addPair(p);

        p = new QuotaPair();
        p.setName(VmQuotaConstant.VOLUME_SIZE);
        p.setValue(VmQuotaGlobalConfig.VOLUME_SIZE.defaultValue(Long.class));
        quota.addPair(p);

        quota.addMessageNeedValidation(APICreateVmInstanceMsg.class);
        quota.addMessageNeedValidation(APIRecoverVmInstanceMsg.class);
        quota.addMessageNeedValidation(APICreateDataVolumeMsg.class);
        quota.addMessageNeedValidation(APIRecoverDataVolumeMsg.class);
        quota.addMessageNeedValidation(APIStartVmInstanceMsg.class);
        quota.addMessageNeedValidation(APIChangeResourceOwnerMsg.class);
        quota.addMessageNeedValidation(StartVmInstanceMsg.class);

        quota.setOperator(checker);

        return list(quota);
    }

    @Override
    @AsyncThread
    public void managementNodeReady() {
        //checkUnknownVm();
        startVmExpungeTask();
    }

    private synchronized void startVmExpungeTask() {
        if (expungeVmTask != null) {
            expungeVmTask.cancel(true);
        }

        expungeVmTask = thdf.submitCancelablePeriodicTask(new CancelablePeriodicTask() {

            private List<Tuple> getVmDeletedStateManagedByUs() {
                int qun = 10000;
                SimpleQuery q = dbf.createQuery(VmInstanceVO.class);
                q.add(VmInstanceVO_.state, Op.EQ, VmInstanceState.Destroyed);
                long amount = q.count();
                int times = (int) (amount / qun) + (amount % qun != 0 ? 1 : 0);
                int start = 0;
                List<Tuple> ret = new ArrayList<>();
                for (int i = 0; i < times; i++) {
                    q = dbf.createQuery(VmInstanceVO.class);
                    q.select(VmInstanceVO_.uuid, VmInstanceVO_.lastOpDate);
                    q.add(VmInstanceVO_.state, Op.EQ, VmInstanceState.Destroyed);
                    q.setLimit(qun);
                    q.setStart(start);
                    List<Tuple> ts = q.listTuple();
                    start += qun;

                    for (Tuple t : ts) {
                        String vmUuid = t.get(0, String.class);
                        if (!destMaker.isManagedByUs(vmUuid)) {
                            continue;
                        }
                        ret.add(t);
                    }
                }

                return ret;
            }

            @Override
            public synchronized boolean run() {
                final List<Tuple> vms = getVmDeletedStateManagedByUs();
                if (vms.isEmpty()) {
                    logger.debug("[VM Expunging Task]: no vm to expunge");
                    return false;
                }

                final Timestamp current = dbf.getCurrentSqlTime();

                final List<ExpungeVmMsg> msgs = CollectionUtils.transformToList(vms, new Function<ExpungeVmMsg, Tuple>() {
                    @Override
                    public ExpungeVmMsg call(Tuple t) {
                        String uuid = t.get(0, String.class);
                        Timestamp date = t.get(1, Timestamp.class);
                        long end = date.getTime() + TimeUnit.SECONDS.toMillis(VmGlobalConfig.VM_EXPUNGE_PERIOD.value(Long.class));
                        if (current.getTime() >= end) {
                            VmInstanceDeletionPolicy deletionPolicy = deletionPolicyMgr.getDeletionPolicy(uuid);

                            if (deletionPolicy == VmInstanceDeletionPolicy.Never) {
                                logger.debug(String.format("[VM Expunging Task]: the deletion policy of the vm[uuid:%s] is Never, don't expunge it",
                                        uuid));
                                return null;
                            } else {
                                ExpungeVmMsg msg = new ExpungeVmMsg();
                                msg.setVmInstanceUuid(uuid);
                                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, uuid);
                                return msg;
                            }
                        } else {
                            return null;
                        }
                    }
                });

                if (msgs.isEmpty()) {
                    logger.debug("[VM Expunging Task]: no vm to expunge");
                    return false;
                }

                bus.send(msgs, 100, new CloudBusListCallBack(null) {
                    @Override
                    public void run(List<MessageReply> replies) {
                        for (MessageReply r : replies) {
                            ExpungeVmMsg msg = msgs.get(replies.indexOf(r));
                            if (!r.isSuccess()) {
                                logger.warn(String.format("failed to expunge the vm[uuid:%s], %s",
                                        msg.getVmInstanceUuid(), r.getError()));
                            } else {
                                logger.debug(String.format("successfully expunged the vm[uuid:%s]",
                                        msg.getVmInstanceUuid()));
                            }
                        }
                    }
                });

                return false;
            }

            @Override
            public TimeUnit getTimeUnit() {
                return TimeUnit.SECONDS;
            }

            @Override
            public long getInterval() {
                return VmGlobalConfig.VM_EXPUNGE_INTERVAL.value(Long.class);
            }

            @Override
            public String getName() {
                return "expunge-vm-task";
            }
        });

        logger.debug(String.format("vm expunging task starts running, [period: %s seconds, interval: %s seconds]",
                VmGlobalConfig.VM_EXPUNGE_PERIOD.value(Long.class), VmGlobalConfig.VM_EXPUNGE_INTERVAL.value(Long.class)));
    }

    @Override
    public String preDeleteL3Network(L3NetworkInventory inventory) throws L3NetworkException {
        return null;
    }

    @Override
    public void beforeDeleteL3Network(L3NetworkInventory inventory) {
    }

    @Override
    public void afterDeleteL3Network(L3NetworkInventory inventory) {
        new StaticIpOperator().deleteStaticIpByL3NetworkUuid(inventory.getUuid());
    }

    @Override
    public void resourceOwnerAfterChange(AccountResourceRefInventory ref, String newOwnerUuid) {
        if (!VmInstanceVO.class.getSimpleName().equals(ref.getResourceType())) {
            return;
        }

        // change root volume
        SimpleQuery<VmInstanceVO> q = dbf.createQuery(VmInstanceVO.class);
        q.select(VmInstanceVO_.rootVolumeUuid);
        q.add(VmInstanceVO_.uuid, Op.EQ, ref.getResourceUuid());
        String rootVolumeUuid = q.findValue();
        if (rootVolumeUuid == null) {
            return;
        }

        acntMgr.changeResourceOwner(rootVolumeUuid, newOwnerUuid);

        // change vmnic(s)
        SimpleQuery<VmNicVO> sq = dbf.createQuery(VmNicVO.class);
        sq.select(VmNicVO_.uuid);
        sq.add(VmNicVO_.vmInstanceUuid, Op.EQ, ref.getResourceUuid());
        List<String> vmnics = sq.listValue();
        if (vmnics.isEmpty()) {
            return;
        }
        for (String vmnicUuid : vmnics) {
            acntMgr.changeResourceOwner(vmnicUuid, newOwnerUuid);
        }

        changeVmCdRomsOwner(ref.getResourceUuid(), newOwnerUuid);
    }

    private void changeVmCdRomsOwner(String vmInstanceUuid, String newOwnerUuid) {
        List<String> vmCdRomUuids = Q.New(VmCdRomVO.class)
                .select(VmCdRomVO_.uuid)
                .eq(VmCdRomVO_.vmInstanceUuid, vmInstanceUuid)
                .listValues();
        if (vmCdRomUuids.isEmpty()) {
            return;
        }

        for (String uuid :vmCdRomUuids) {
            acntMgr.changeResourceOwner(uuid, newOwnerUuid);
        }
    }

    @Override
    public List<Class> getMessageClassToIntercept() {
        return asList(APIChangeResourceOwnerMsg.class);
    }

    @Override
    public InterceptorPosition getPosition() {
        return InterceptorPosition.END;
    }

    @Override
    public APIMessage intercept(APIMessage msg) throws ApiMessageInterceptionException {
        if (msg instanceof APIChangeResourceOwnerMsg) {
            validateAPIChangeResourceOwnerMsg((APIChangeResourceOwnerMsg) msg);
        }

        return msg;
    }

    private void validateAPIChangeResourceOwnerMsg(APIChangeResourceOwnerMsg msg) {
        SimpleQuery<AccountResourceRefVO> q = dbf.createQuery(AccountResourceRefVO.class);
        q.add(AccountResourceRefVO_.resourceUuid, Op.EQ, msg.getResourceUuid());
        AccountResourceRefVO ref = q.find();

        if (ref == null || !VolumeVO.class.getSimpleName().equals(ref.getResourceType())) {
            return;
        }

        SimpleQuery<VolumeVO> vq = dbf.createQuery(VolumeVO.class);
        vq.add(VolumeVO_.uuid, Op.EQ, ref.getResourceUuid());
        vq.add(VolumeVO_.type, Op.EQ, VolumeType.Root);
        if (vq.isExists()) {
            throw new OperationFailureException(operr("the resource[uuid:%s] is a ROOT volume, you cannot change its owner, instead," +
                            "change the owner of the VM the root volume belongs to", ref.getResourceUuid()));
        }
    }

    @Override
    public void afterChangeHostStatus(String hostUuid, HostStatus before, HostStatus next) {
        if(next == HostStatus.Disconnected) {
            List<String> vmUuids = Q.New(VmInstanceVO.class).select(VmInstanceVO_.uuid)
                    .eq(VmInstanceVO_.hostUuid, hostUuid)
                    .listValues();
            if(vmUuids.isEmpty()){
                return;
            }

            new While<>(vmUuids).step((vmUuid, completion) -> {
                VmStateChangedOnHostMsg msg = new VmStateChangedOnHostMsg();
                msg.setVmInstanceUuid(vmUuid);
                msg.setHostUuid(hostUuid);
                msg.setStateOnHost(VmInstanceState.Unknown);
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, vmUuid);
                bus.send(msg, new CloudBusCallBack(completion) {
                    @Override
                    public void run(MessageReply reply) {
                        if(!reply.isSuccess()){
                            logger.warn(String.format("the host[uuid:%s] disconnected, but the vm[uuid:%s] fails to " +
                                            "change it's state to Unknown, %s", hostUuid, vmUuid, reply.getError()));
                        } else {
                            logger.debug(String.format("the host[uuid:%s] disconnected, change the VM[uuid:%s]' state to Unknown", hostUuid, vmUuid));
                        }
                        completion.done();
                    }
                });
            }, 200).run(new NopeNoErrorCompletion());
        }
    }

    @Override
    public void afterHostConnected(HostInventory inv) {
        if (inv.getStatus().equals(HostStatus.Connected.toString())){
            List<String> vmUuids = Q.New(VmInstanceVO.class).select(VmInstanceVO_.uuid)
                    .eq(VmInstanceVO_.hostUuid, inv.getUuid())
                    .listValues();
            if(vmUuids.isEmpty()){
                return;
            }
            new While<>(vmUuids).step((vmUuid, completion) -> {
                VmCheckOwnStateMsg msg = new VmCheckOwnStateMsg();
                msg.setVmInstanceUuid(vmUuid);
                bus.makeTargetServiceIdByResourceUuid(msg, VmInstanceConstant.SERVICE_ID, vmUuid);
                bus.send(msg, new CloudBusCallBack(completion) {
                    @Override
                    public void run(MessageReply reply) {
                        if(!reply.isSuccess()){
                            logger.warn(String.format("the host[uuid:%s] connected, but the vm[uuid:%s] fails to " +
                                    "update it's state , %s", inv.getUuid(), vmUuid, reply.getError()));
                        }
                        completion.done();
                    }
                });
            }, 200).run(new NopeNoErrorCompletion());
        }

    }
}
