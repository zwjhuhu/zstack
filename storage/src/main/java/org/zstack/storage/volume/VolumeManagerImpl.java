package org.zstack.storage.volume;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.zstack.core.Platform;
import org.zstack.core.cloudbus.CloudBus;
import org.zstack.core.cloudbus.CloudBusCallBack;
import org.zstack.core.cloudbus.MessageSafe;
import org.zstack.core.cloudbus.ResourceDestinationMaker;
import org.zstack.core.componentloader.PluginRegistry;
import org.zstack.core.config.GlobalConfig;
import org.zstack.core.config.GlobalConfigUpdateExtensionPoint;
import org.zstack.core.db.*;
import org.zstack.core.db.SimpleQuery.Op;
import org.zstack.core.thread.CancelablePeriodicTask;
import org.zstack.core.thread.ThreadFacade;
import org.zstack.core.workflow.FlowChainBuilder;
import org.zstack.core.workflow.ShareFlow;
import org.zstack.header.AbstractService;
import org.zstack.header.configuration.DiskOfferingVO;
import org.zstack.header.configuration.DiskOfferingVO_;
import org.zstack.header.core.Completion;
import org.zstack.header.core.workflow.*;
import org.zstack.header.errorcode.ErrorCode;
import org.zstack.header.errorcode.OperationFailureException;
import org.zstack.header.identity.AccountResourceRefInventory;
import org.zstack.header.identity.ResourceOwnerAfterChangeExtensionPoint;
import org.zstack.header.image.*;
import org.zstack.header.managementnode.ManagementNodeReadyExtensionPoint;
import org.zstack.header.message.APIMessage;
import org.zstack.header.message.Message;
import org.zstack.header.message.MessageReply;
import org.zstack.header.storage.backup.BackupStorageState;
import org.zstack.header.storage.backup.BackupStorageStatus;
import org.zstack.header.storage.primary.*;
import org.zstack.header.storage.snapshot.*;
import org.zstack.header.vm.VmInstanceInventory;
import org.zstack.header.vm.VmInstanceState;
import org.zstack.header.vm.VmInstanceVO;
import org.zstack.header.vm.VmStateChangedExtensionPoint;
import org.zstack.header.volume.*;
import org.zstack.header.volume.APIGetVolumeFormatReply.VolumeFormatReplyStruct;
import org.zstack.header.volume.VolumeDeletionPolicyManager.VolumeDeletionPolicy;
import org.zstack.identity.AccountManager;
import org.zstack.storage.primary.PrimaryStorageDeleteBitGC;
import org.zstack.storage.primary.PrimaryStorageGlobalConfig;
import org.zstack.tag.TagManager;
import org.zstack.utils.CollectionUtils;
import org.zstack.utils.Utils;
import org.zstack.utils.function.Function;
import org.zstack.utils.logging.CLogger;

import javax.persistence.Tuple;
import javax.persistence.TypedQuery;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.zstack.core.Platform.operr;

public class VolumeManagerImpl extends AbstractService implements VolumeManager, ManagementNodeReadyExtensionPoint,
        VolumeDeletionExtensionPoint, VolumeBeforeExpungeExtensionPoint, RecoverDataVolumeExtensionPoint,
        ResourceOwnerAfterChangeExtensionPoint, VmStateChangedExtensionPoint {
    private static final CLogger logger = Utils.getLogger(VolumeManagerImpl.class);

    @Autowired
    private CloudBus bus;
    @Autowired
    private DatabaseFacade dbf;
    @Autowired
    private AccountManager acntMgr;
    @Autowired
    private TagManager tagMgr;
    @Autowired
    private ThreadFacade thdf;
    @Autowired
    private ResourceDestinationMaker destMaker;
    @Autowired
    private VolumeDeletionPolicyManager deletionPolicyMgr;
    @Autowired
    private PluginRegistry pluginRgty;

    private Future<Void> volumeExpungeTask;

    private void passThrough(VolumeMessage vmsg) {
        Message msg = (Message) vmsg;
        VolumeVO vo = dbf.findByUuid(vmsg.getVolumeUuid(), VolumeVO.class);
        if (vo == null) {
            bus.replyErrorByMessageType(msg, String.format("Cannot find volume[uuid:%s], it may have been deleted", vmsg.getVolumeUuid()));
            return;
        }

        List<VolumeFactory> l = pluginRgty.getExtensionList(VolumeFactory.class);
        if (!l.isEmpty()) {
            VolumeBase volumeBase = l.get(0).makeVolumeBase(vo);
            volumeBase.handleMessage(msg);
        } else {
            VolumeBase volumeBase = new VolumeBase(vo);
            volumeBase.handleMessage(msg);
        }
    }

    @Override
    @MessageSafe
    public void handleMessage(Message msg) {
        if (msg instanceof VolumeMessage) {
            passThrough((VolumeMessage) msg);
        } else if (msg instanceof APIMessage) {
            handleApiMessage((APIMessage) msg);
        } else {
            handleLocalMessage(msg);
        }
    }

    private void handleLocalMessage(Message msg) {
        if (msg instanceof CreateVolumeMsg) {
            handle((CreateVolumeMsg) msg);
        } else if (msg instanceof VolumeReportPrimaryStorageCapacityUsageMsg) {
            handle((VolumeReportPrimaryStorageCapacityUsageMsg) msg);
        } else if (msg instanceof CreateDataVolumeFromVolumeTemplateMsg) {
            handle((CreateDataVolumeFromVolumeTemplateMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(CreateDataVolumeFromVolumeTemplateMsg msg) {
        CreateDataVolumeFromVolumeTemplateReply reply = new CreateDataVolumeFromVolumeTemplateReply();

        final ImageVO template = dbf.findByUuid(msg.getImageUuid(), ImageVO.class);
        final VolumeVO vol = new VolumeVO();
        vol.setUuid(msg.getResourceUuid() == null ? Platform.getUuid() : msg.getResourceUuid());
        vol.setName(msg.getName());
        vol.setDescription(msg.getDescription());
        vol.setFormat(template.getFormat());
        vol.setSize(template.getSize());
        vol.setActualSize(template.getActualSize());
        vol.setRootImageUuid(template.getUuid());
        vol.setStatus(VolumeStatus.Creating);
        vol.setState(VolumeState.Enabled);
        vol.setType(VolumeType.Data);
        vol.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        vol.setAccountUuid(msg.getSession().getAccountUuid());
        VolumeVO vvo = new SQLBatchWithReturn<VolumeVO>() {
            @Override
            protected VolumeVO scripts() {
                persist(vol);
                reload(vol);
                if (msg.getApiMsg() != null) {
                    tagMgr.createTagsFromAPICreateMessage(msg.getApiMsg(), vol.getUuid(), VolumeVO.class.getSimpleName());
                }
                return vol;
            }
        }.execute();

        if (msg.getSystemTags() != null) {
            tagMgr.createNonInherentSystemTags(msg.getSystemTags(), vvo.getUuid(), VolumeVO.class.getSimpleName());
        }

        new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(null, VolumeInventory.valueOf(vvo));

        FlowChain chain = FlowChainBuilder.newShareFlowChain();
        chain.setName(String.format("create-data-volume-from-template-%s", template.getUuid()));
        chain.then(new ShareFlow() {
            ImageBackupStorageRefVO targetBackupStorageRef;
            PrimaryStorageInventory targetPrimaryStorage;
            String primaryStorageInstallPath;
            String prePSInstallPath;
            String volumeFormat;

            @Override
            public void setup() {
                flow(new NoRollbackFlow() {
                    String __name__ = "select-backup-storage";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        List<String> bsUuids = new SQLBatchWithReturn<List<String>>() {
                            @Override
                            protected List<String> scripts() {
                                List<String> bsUuids = CollectionUtils.transformToList(template.getBackupStorageRefs(), new Function<String, ImageBackupStorageRefVO>() {
                                    @Override
                                    public String call(ImageBackupStorageRefVO arg) {
                                        return ImageStatus.Deleted.equals(arg.getStatus()) ? null : arg.getBackupStorageUuid();
                                    }
                                });

                                if (bsUuids.isEmpty()) {
                                    throw new OperationFailureException(operr("the image[uuid:%s, name:%s] has been deleted on all backup storage", template.getUuid(), template.getName()));
                                }

                                String sql = "select bs.uuid from BackupStorageVO bs, BackupStorageZoneRefVO zref, PrimaryStorageVO ps where zref.zoneUuid = ps.zoneUuid and bs.status = :bsStatus and bs.state = :bsState and ps.uuid = :psUuid and zref.backupStorageUuid = bs.uuid and bs.uuid in (:bsUuids)";
                                TypedQuery<String> q = dbf.getEntityManager().createQuery(sql, String.class);
                                q.setParameter("psUuid", msg.getPrimaryStorageUuid());
                                q.setParameter("bsStatus", BackupStorageStatus.Connected);
                                q.setParameter("bsState", BackupStorageState.Enabled);
                                q.setParameter("bsUuids", bsUuids);
                                bsUuids = q.getResultList();

                                return bsUuids;
                            }
                        }.execute();


                        if (bsUuids.isEmpty()) {
                            trigger.fail(operr("cannot find a backup storage on which the image[uuid:%s] is that satisfies all conditions of: 1. has state Enabled 2. has status Connected. 3 has attached to zone in which primary storage[uuid:%s] is",
                                    template.getUuid(), msg.getPrimaryStorageUuid()));
                            return;
                        }

                        final String bsUuid = bsUuids.get(0);
                        targetBackupStorageRef = CollectionUtils.find(template.getBackupStorageRefs(), new Function<ImageBackupStorageRefVO, ImageBackupStorageRefVO>() {
                            @Override
                            public ImageBackupStorageRefVO call(ImageBackupStorageRefVO arg) {
                                return arg.getBackupStorageUuid().equals(bsUuid) ? arg : null;
                            }
                        });

                        trigger.next();
                    }
                });

                flow(new Flow() {
                    String __name__ = "allocate-primary-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        AllocatePrimaryStorageMsg amsg = new AllocatePrimaryStorageMsg();
                        amsg.setSize(template.getSize());
                        amsg.setPurpose(PrimaryStorageAllocationPurpose.DownloadImage.toString());
                        amsg.setRequiredPrimaryStorageUuid(msg.getPrimaryStorageUuid());
                        amsg.setRequiredHostUuid(msg.getHostUuid());
                        bus.makeLocalServiceId(amsg, PrimaryStorageConstant.SERVICE_ID);
                        bus.send(amsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                } else {
                                    targetPrimaryStorage = ((AllocatePrimaryStorageReply) reply).getPrimaryStorageInventory();
                                    trigger.next();
                                }
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (targetPrimaryStorage != null) {
                            IncreasePrimaryStorageCapacityMsg imsg = new IncreasePrimaryStorageCapacityMsg();
                            imsg.setDiskSize(template.getSize());
                            imsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                            bus.makeTargetServiceIdByResourceUuid(imsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                            bus.send(imsg);
                        }
                        trigger.rollback();
                    }
                });

                flow(new NoRollbackFlow() {
                    String __name__ = "get-download-data-volume-template-to-primary-storage-for-garbage";

                    @Override
                    public void run(FlowTrigger trigger, Map data) {
                        GetInstallPathForDataVolumeDownloadMsg gmsg = new GetInstallPathForDataVolumeDownloadMsg();
                        gmsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                        gmsg.setVolumeUuid(vol.getUuid());
                        gmsg.setBackupStorageRef(ImageBackupStorageRefInventory.valueOf(targetBackupStorageRef));
                        gmsg.setImage(ImageInventory.valueOf(template));
                        gmsg.setHostUuid(msg.getHostUuid());
                        bus.makeTargetServiceIdByResourceUuid(gmsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                        bus.send(gmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                } else {
                                    GetInstallPathForDataVolumeDownloadReply r = reply.castReply();
                                    prePSInstallPath = r.getInstallPath();
                                    trigger.next();
                                }
                            }
                        });
                    }
                });

                flow(new Flow() {
                    String __name__ = "download-data-volume-template-to-primary-storage";

                    @Override
                    public void run(final FlowTrigger trigger, Map data) {
                        DownloadDataVolumeToPrimaryStorageMsg dmsg = new DownloadDataVolumeToPrimaryStorageMsg();
                        dmsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                        dmsg.setVolumeUuid(vol.getUuid());
                        dmsg.setBackupStorageRef(ImageBackupStorageRefInventory.valueOf(targetBackupStorageRef));
                        dmsg.setImage(ImageInventory.valueOf(template));
                        dmsg.setHostUuid(msg.getHostUuid());
                        bus.makeTargetServiceIdByResourceUuid(dmsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                        bus.send(dmsg, new CloudBusCallBack(trigger) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    trigger.fail(reply.getError());
                                } else {
                                    DownloadDataVolumeToPrimaryStorageReply r = reply.castReply();
                                    primaryStorageInstallPath = r.getInstallPath();
                                    volumeFormat = r.getFormat();
                                    trigger.next();
                                }
                            }
                        });
                    }

                    @Override
                    public void rollback(FlowRollback trigger, Map data) {
                        if (primaryStorageInstallPath != null) {
                            // if primaryStorageInstallPath != null, then delete it directly
                            DeleteVolumeBitsOnPrimaryStorageMsg delMsg = new DeleteVolumeBitsOnPrimaryStorageMsg();
                            delMsg.setInstallPath(primaryStorageInstallPath);
                            delMsg.setBitsUuid(vol.getUuid());
                            delMsg.setBitsType(VolumeVO.class.getSimpleName());
                            delMsg.setPrimaryStorageUuid(targetPrimaryStorage.getUuid());
                            delMsg.setHypervisorType(VolumeFormat.getMasterHypervisorTypeByVolumeFormat(vol.getFormat()).toString());
                            bus.makeTargetServiceIdByResourceUuid(delMsg, PrimaryStorageConstant.SERVICE_ID, targetPrimaryStorage.getUuid());
                            bus.send(delMsg);
                        } else if (PrimaryStorageGlobalConfig.PRIMARY_STORAGE_DELETEBITS_ON.value(Boolean.class)) {
                            // if primaryStorageInstallPath == null, we don't know the status agent running, so use garbage to delete
                            PrimaryStorageDeleteBitGC gc = new PrimaryStorageDeleteBitGC();
                            gc.NAME = String.format("gc-delete-bits-volume-%s-on-primary-storage-%s", vol.getUuid(), targetPrimaryStorage.getUuid());
                            gc.primaryStorageInstallPath = prePSInstallPath;
                            gc.primaryStorageUuid = targetPrimaryStorage.getUuid();
                            gc.volume = vol;
                            gc.submit(PrimaryStorageGlobalConfig.PRIMARY_STORAGE_DELETEBITS_GARBAGE_COLLECTOR_INTERVAL.value(Long.class),
                                    TimeUnit.SECONDS);
                        }
                        trigger.rollback();
                    }
                });

                done(new FlowDoneHandler(msg) {
                    @Override
                    public void handle(Map data) {
                        vol.setInstallPath(primaryStorageInstallPath);
                        vol.setStatus(VolumeStatus.Ready);
                        if (volumeFormat != null) {
                            vol.setFormat(volumeFormat);
                        }
                        VolumeVO vo = dbf.updateAndRefresh(vol);

                        new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(VolumeStatus.Creating, VolumeInventory.valueOf(vo));

                        reply.setInventory(VolumeInventory.valueOf(vo));
                        bus.reply(msg, reply);
                    }
                });

                error(new FlowErrorHandler(msg) {
                    @Override
                    public void handle(ErrorCode errCode, Map data) {
                        reply.setError(errCode);
                        reply.setSuccess(false);
                        dbf.removeByPrimaryKey(vol.getUuid(), vol.getClass());
                        bus.reply(msg, reply);
                    }
                });
            }
        }).start();
    }

    @Transactional(readOnly = true)
    private void handle(VolumeReportPrimaryStorageCapacityUsageMsg msg) {
        String sql = "select sum(vol.size) from VolumeVO vol where vol.primaryStorageUuid = :prUuid and vol.status = :status";
        TypedQuery<Long> q = dbf.getEntityManager().createQuery(sql, Long.class);
        q.setParameter("prUuid", msg.getPrimaryStorageUuid());
        q.setParameter("status", VolumeStatus.Ready);
        Long size = q.getSingleResult();

        VolumeReportPrimaryStorageCapacityUsageReply reply = new VolumeReportPrimaryStorageCapacityUsageReply();
        reply.setUsedCapacity(size == null ? 0 : size);
        bus.reply(msg, reply);
    }

    private VolumeInventory createVolume(CreateVolumeMsg msg) {
        VolumeVO vo = new VolumeVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setRootImageUuid(msg.getRootImageUuid());
        vo.setDescription(msg.getDescription());
        vo.setName(msg.getName());
        vo.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        vo.setSize(msg.getSize());
        vo.setActualSize(msg.getActualSize());
        vo.setVmInstanceUuid(msg.getVmInstanceUuid());
        vo.setFormat(msg.getFormat());
        vo.setStatus(VolumeStatus.NotInstantiated);
        vo.setType(VolumeType.valueOf(msg.getVolumeType()));
        vo.setDiskOfferingUuid(msg.getDiskOfferingUuid());
        if (vo.getType() == VolumeType.Root) {
            vo.setDeviceId(0);
        }
        vo.setAccountUuid(msg.getAccountUuid());

        VolumeVO finalVo = vo;
        vo = new SQLBatchWithReturn<VolumeVO>() {
            @Override
            protected VolumeVO scripts() {
                dbf.getEntityManager().persist(finalVo);
                dbf.getEntityManager().flush();
                dbf.getEntityManager().refresh(finalVo);
                return finalVo;
            }
        }.execute();
        if (msg.getSystemTags() != null) {
            tagMgr.createNonInherentSystemTags(msg.getSystemTags(), vo.getUuid(), VolumeVO.class.getSimpleName());
        }

        List<CreateDataVolumeExtensionPoint> exts = pluginRgty.getExtensionList(CreateDataVolumeExtensionPoint.class);
        for (CreateDataVolumeExtensionPoint ext : exts) {
            ext.afterCreateVolume(vo);
        }
        vo = dbf.reload(vo);

        new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(null, VolumeInventory.valueOf(vo));

        VolumeInventory inv = VolumeInventory.valueOf(vo);
        logger.debug(String.format("successfully created volume[uuid:%s, name:%s, type:%s, vm uuid:%s",
                inv.getUuid(), inv.getName(), inv.getType(), inv.getVmInstanceUuid()));
        return inv;
    }

    private void handle(CreateVolumeMsg msg) {
        VolumeInventory inv = createVolume(msg);
        CreateVolumeReply reply = new CreateVolumeReply();
        reply.setInventory(inv);
        bus.reply(msg, reply);
    }

    private void handle(APICreateDataVolumeFromVolumeSnapshotMsg msg) {
        final APICreateDataVolumeFromVolumeSnapshotEvent evt = new APICreateDataVolumeFromVolumeSnapshotEvent(msg.getId());
        final VolumeVO vo = new VolumeVO();
        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setName(msg.getName());
        vo.setDescription(msg.getDescription());
        vo.setState(VolumeState.Enabled);
        vo.setStatus(VolumeStatus.Creating);
        vo.setType(VolumeType.Data);
        vo.setSize(0);
        vo.setAccountUuid(msg.getSession().getAccountUuid());
        VolumeVO vvo = new SQLBatchWithReturn<VolumeVO>() {
            @Override
            protected VolumeVO scripts() {
                persist(vo);
                reload(vo);
                tagMgr.createTagsFromAPICreateMessage(msg, vo.getUuid(), VolumeVO.class.getSimpleName());
                return vo;
            }
        }.execute();

        new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(null, VolumeInventory.valueOf(vvo));

        SimpleQuery<VolumeSnapshotVO> sq = dbf.createQuery(VolumeSnapshotVO.class);
        sq.select(VolumeSnapshotVO_.volumeUuid, VolumeSnapshotVO_.treeUuid);
        sq.add(VolumeSnapshotVO_.uuid, Op.EQ, msg.getVolumeSnapshotUuid());
        Tuple t = sq.findTuple();
        String volumeUuid = t.get(0, String.class);
        String treeUuid = t.get(1, String.class);

        CreateDataVolumeFromVolumeSnapshotMsg cmsg = new CreateDataVolumeFromVolumeSnapshotMsg();
        cmsg.setVolumeUuid(volumeUuid);
        cmsg.setTreeUuid(treeUuid);
        cmsg.setUuid(msg.getVolumeSnapshotUuid());
        cmsg.setVolume(VolumeInventory.valueOf(vo));
        cmsg.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        String resourceUuid = volumeUuid != null ? volumeUuid : treeUuid;
        bus.makeTargetServiceIdByResourceUuid(cmsg, VolumeSnapshotConstant.SERVICE_ID, resourceUuid);
        bus.send(cmsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (reply.isSuccess()) {
                    CreateDataVolumeFromVolumeSnapshotReply cr = reply.castReply();
                    VolumeInventory inv = cr.getInventory();
                    vo.setSize(inv.getSize());
                    vo.setActualSize(cr.getActualSize());
                    vo.setInstallPath(inv.getInstallPath());
                    vo.setStatus(VolumeStatus.Ready);
                    vo.setPrimaryStorageUuid(inv.getPrimaryStorageUuid());
                    vo.setFormat(inv.getFormat());
                    VolumeVO vvo = dbf.updateAndRefresh(vo);

                    new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(VolumeStatus.Creating, VolumeInventory.valueOf(vvo));

                    evt.setInventory(VolumeInventory.valueOf(vvo));
                } else {
                    evt.setError(reply.getError());
                }

                bus.publish(evt);
            }
        });
    }

    private void handleApiMessage(APIMessage msg) {
        if (msg instanceof APICreateDataVolumeMsg) {
            handle((APICreateDataVolumeMsg) msg);
        } else if (msg instanceof APICreateDataVolumeFromVolumeSnapshotMsg) {
            handle((APICreateDataVolumeFromVolumeSnapshotMsg) msg);
        } else if (msg instanceof APICreateDataVolumeFromVolumeTemplateMsg) {
            handle((APICreateDataVolumeFromVolumeTemplateMsg) msg);
        } else if (msg instanceof APIGetVolumeFormatMsg) {
            handle((APIGetVolumeFormatMsg) msg);
        } else {
            bus.dealWithUnknownMessage(msg);
        }
    }

    private void handle(APIGetVolumeFormatMsg msg) {
        List<VolumeFormatReplyStruct> structs = new ArrayList<VolumeFormatReplyStruct>();
        for (VolumeFormat format : VolumeFormat.getAllFormats()) {
            structs.add(new VolumeFormatReplyStruct(format));
        }

        APIGetVolumeFormatReply reply = new APIGetVolumeFormatReply();
        reply.setFormats(structs);
        bus.reply(msg, reply);
    }

    private void handle(final APICreateDataVolumeFromVolumeTemplateMsg msg) {
        final APICreateDataVolumeFromVolumeTemplateEvent evt = new APICreateDataVolumeFromVolumeTemplateEvent(msg.getId());
        CreateDataVolumeFromVolumeTemplateMsg cmsg = new CreateDataVolumeFromVolumeTemplateMsg(msg);
        bus.makeLocalServiceId(cmsg, VolumeConstant.SERVICE_ID);
        bus.send(cmsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    evt.setSuccess(false);
                    evt.setError(reply.getError());
                    bus.publish(evt);
                    return;
                }
                CreateDataVolumeFromVolumeTemplateReply reply1 = reply.castReply();
                if (!reply1.isSuccess()) {
                    evt.setSuccess(false);
                    evt.setError(reply1.getError());
                    bus.publish(evt);
                    return;
                }

                evt.setInventory(reply1.getInventory());
                bus.publish(evt);
            }
        });
    }

    private void handle(APICreateDataVolumeMsg msg) {
        APICreateDataVolumeEvent evt = new APICreateDataVolumeEvent(msg.getId());
        pluginRgty.getExtensionList(CreateDataVolumeExtensionPoint.class).forEach(extensionPoint -> {
            extensionPoint.preCreateVolume(msg);
        });

        VolumeVO vo = new VolumeVO();

        final String diskOfferingUuid = msg.getDiskOfferingUuid();
        if (diskOfferingUuid != null) {
            Long diskSize = Q.New(DiskOfferingVO.class).eq(DiskOfferingVO_.uuid, diskOfferingUuid).select(DiskOfferingVO_.diskSize).findValue();
            vo.setDiskOfferingUuid(diskOfferingUuid);
            vo.setSize(diskSize);
        } else {
            vo.setSize(msg.getDiskSize());
        }

        if (msg.getResourceUuid() != null) {
            vo.setUuid(msg.getResourceUuid());
        } else {
            vo.setUuid(Platform.getUuid());
        }
        vo.setDescription(msg.getDescription());
        vo.setName(msg.getName());
        vo.setActualSize(0L);
        vo.setType(VolumeType.Data);
        vo.setStatus(VolumeStatus.NotInstantiated);
        vo.setAccountUuid(msg.getSession().getAccountUuid());

        if (msg.hasSystemTag(VolumeSystemTags.SHAREABLE.getTagFormat())) {
            vo.setShareable(true);
        }
        List<CreateDataVolumeExtensionPoint> exts = pluginRgty.getExtensionList(CreateDataVolumeExtensionPoint.class);
        for (CreateDataVolumeExtensionPoint ext : exts) {
            ext.beforeCreateVolume(VolumeInventory.valueOf(vo));
        }

        VolumeVO finalVo1 = vo;
        vo = new SQLBatchWithReturn<VolumeVO>() {
            @Override
            protected VolumeVO scripts() {
                dbf.getEntityManager().persist(finalVo1);
                dbf.getEntityManager().flush();
                dbf.getEntityManager().refresh(finalVo1);
                return finalVo1;
            }
        }.execute();

        tagMgr.createTagsFromAPICreateMessage(msg, finalVo1.getUuid(), VolumeVO.class.getSimpleName());
        for (CreateDataVolumeExtensionPoint ext : exts) {
            ext.afterCreateVolume(vo);
        }
        dbf.reload(vo);

        if (msg.getPrimaryStorageUuid() == null) {
            new FireVolumeCanonicalEvent().fireVolumeStatusChangedEvent(null, VolumeInventory.valueOf(vo));

            VolumeInventory inv = VolumeInventory.valueOf(vo);
            evt.setInventory(inv);
            logger.debug(String.format("Successfully created data volume[name:%s, uuid:%s, size:%s]", inv.getName(), inv.getUuid(), inv.getSize()));
            bus.publish(evt);
            return;
        }

        InstantiateVolumeMsg imsg = new InstantiateVolumeMsg();
        imsg.setVolumeUuid(vo.getUuid());
        imsg.setPrimaryStorageUuid(msg.getPrimaryStorageUuid());
        imsg.setSystemTags(msg.getSystemTags());
        imsg.setUserTags(msg.getUserTags());
        bus.makeTargetServiceIdByResourceUuid(imsg, VolumeConstant.SERVICE_ID, vo.getUuid());
        VolumeVO finalVo = vo;
        bus.send(imsg, new CloudBusCallBack(msg) {
            @Override
            public void run(MessageReply reply) {
                if (!reply.isSuccess()) {
                    dbf.remove(finalVo);
                    evt.setError(reply.getError());
                } else {
                    evt.setInventory(((InstantiateVolumeReply) reply).getVolume());
                }

                bus.publish(evt);
            }
        });
    }

    @Override
    public String getId() {
        return bus.makeLocalServiceId(VolumeConstant.SERVICE_ID);
    }

    @Override
    public boolean start() {
        VolumeGlobalConfig.VOLUME_EXPUNGE_INTERVAL.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startExpungeTask();
            }
        });

        VolumeGlobalConfig.VOLUME_EXPUNGE_PERIOD.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startExpungeTask();
            }
        });

        VolumeGlobalConfig.VOLUME_DELETION_POLICY.installUpdateExtension(new GlobalConfigUpdateExtensionPoint() {
            @Override
            public void updateGlobalConfig(GlobalConfig oldConfig, GlobalConfig newConfig) {
                startExpungeTask();
            }
        });

        pluginRgty.saveExtensionAsMap(InstantiateDataVolumeOnCreationExtensionPoint.class, new Function<Object, InstantiateDataVolumeOnCreationExtensionPoint>() {
            @Override
            public Object call(InstantiateDataVolumeOnCreationExtensionPoint arg) {
                return arg.getPrimaryStorageTypeForInstantiateDataVolumeOnCreationExtensionPoint();
            }
        });

        {
            List<VolumeFactory> exts = pluginRgty.getExtensionList(
                    VolumeFactory.class);
            if (exts.size() > 1) {
                throw new OperationFailureException(operr("there should not be more than one %s implementation.",
                        VolumeFactory.class.getSimpleName()));
            }
        }

        VolumeInventory.setAttachedJudgers(pluginRgty.getExtensionList(VolumeAttachedJudger.class));

        return true;
    }

    @Override
    public boolean stop() {
        return true;
    }

    private synchronized void startExpungeTask() {
        if (volumeExpungeTask != null) {
            volumeExpungeTask.cancel(true);
        }

        volumeExpungeTask = thdf.submitCancelablePeriodicTask(new CancelablePeriodicTask() {
            private List<Tuple> getDeletedVolumeManagedByUs() {
                int qun = 1000;
                SimpleQuery q = dbf.createQuery(VolumeVO.class);
                q.add(VolumeVO_.status, Op.EQ, VolumeStatus.Deleted);
                q.add(VolumeVO_.type, Op.EQ, VolumeType.Data);
                long amount = q.count();
                int times = (int) (amount / qun) + (amount % qun != 0 ? 1 : 0);
                int start = 0;
                List<Tuple> ret = new ArrayList<Tuple>();
                for (int i = 0; i < times; i++) {
                    q = dbf.createQuery(VolumeVO.class);
                    q.select(VolumeVO_.uuid, VolumeVO_.lastOpDate);
                    q.add(VolumeVO_.status, Op.EQ, VolumeStatus.Deleted);
                    q.add(VolumeVO_.type, Op.EQ, VolumeType.Data);
                    q.setLimit(qun);
                    q.setStart(start);
                    List<Tuple> lst = q.listTuple();
                    start += qun;
                    for (Tuple t : lst) {
                        String uuid = t.get(0, String.class);
                        if (!destMaker.isManagedByUs(uuid)) {
                            continue;
                        }

                        ret.add(t);
                    }
                }

                return ret;
            }

            @Override
            public boolean run() {
                List<Tuple> vols = getDeletedVolumeManagedByUs();
                if (vols.isEmpty()) {
                    logger.debug("[Volume Expunging Task]: no volume to expunge");
                    return false;
                }

                Timestamp current = dbf.getCurrentSqlTime();
                for (final Tuple v : vols) {
                    final String uuid = v.get(0, String.class);
                    Timestamp date = v.get(1, Timestamp.class);
                    long end = date.getTime() + TimeUnit.SECONDS.toMillis(VolumeGlobalConfig.VOLUME_EXPUNGE_PERIOD.value(Long.class));
                    if (current.getTime() >= end) {

                        VolumeDeletionPolicy deletionPolicy = deletionPolicyMgr.getDeletionPolicy(uuid);
                        if (deletionPolicy == VolumeDeletionPolicy.Never) {
                            logger.debug(String.format("the deletion policy of the volume[uuid:%s] is Never, don't expunge it",
                                    uuid));
                            continue;
                        }

                        ExpungeVolumeMsg msg = new ExpungeVolumeMsg();
                        msg.setVolumeUuid(uuid);
                        bus.makeTargetServiceIdByResourceUuid(msg, VolumeConstant.SERVICE_ID, uuid);
                        bus.send(msg, new CloudBusCallBack(null) {
                            @Override
                            public void run(MessageReply reply) {
                                if (!reply.isSuccess()) {
                                    logger.warn(String.format("failed to expunge the volume[uuid:%s], %s", uuid, reply.getError()));
                                } else {
                                    logger.debug(String.format("successfully expunged the volume [uuid:%s]", uuid));
                                }
                            }
                        });

                    }
                }

                return false;
            }

            @Override
            public TimeUnit getTimeUnit() {
                return TimeUnit.SECONDS;
            }

            @Override
            public long getInterval() {
                return VolumeGlobalConfig.VOLUME_EXPUNGE_INTERVAL.value(Long.class);
            }

            @Override
            public String getName() {
                return "expunging-volume-task";
            }
        });

        logger.debug(String.format("volume expunging task starts [period: %s seconds, interval: %s seconds]",
                VolumeGlobalConfig.VOLUME_EXPUNGE_PERIOD.value(Long.class),
                VolumeGlobalConfig.VOLUME_EXPUNGE_INTERVAL.value(Long.class)));
    }

    @Override
    public void managementNodeReady() {
        startExpungeTask();
    }

    public void preDeleteVolume(VolumeInventory volume) {

    }

    public void beforeDeleteVolume(VolumeInventory volume) {

    }

    public void afterDeleteVolume(VolumeInventory volume, Completion completion) {
        completion.success();
    }

    public void failedToDeleteVolume(VolumeInventory volume, ErrorCode errorCode) {

    }

    public void volumeBeforeExpunge(VolumeInventory volume) {

    }

    public void preRecoverDataVolume(VolumeInventory volume) {

    }

    public void beforeRecoverDataVolume(VolumeInventory volume) {
    }

    public void afterRecoverDataVolume(VolumeInventory volume) {
    }


    @Override
    public void resourceOwnerAfterChange(AccountResourceRefInventory ref, String newOwnerUuid) {
        if (!VmInstanceVO.class.getSimpleName().equals(ref.getResourceType())) {
            return;
        }

        changeVolumeOwner(ref, newOwnerUuid);
    }

    private void changeVolumeOwner(AccountResourceRefInventory ref, String newOwnerUuid) {
        SimpleQuery<VolumeVO> q = dbf.createQuery(VolumeVO.class);
        q.select(VolumeVO_.uuid);
        q.add(VolumeVO_.vmInstanceUuid, Op.EQ, ref.getResourceUuid());
        List<String> uuids = q.listValue();

        for (String uuid : uuids) {
            acntMgr.changeResourceOwner(uuid, newOwnerUuid);
        }
    }

    @Override
    public void vmStateChanged(VmInstanceInventory vm, VmInstanceState oldState, VmInstanceState newState) {
        if (newState == VmInstanceState.Destroyed && vm != null && vm.getRootVolumeUuid() != null) {
            SQL.New(VolumeVO.class).eq(VolumeVO_.uuid, vm.getRootVolumeUuid())
                    .set(VolumeVO_.status, VolumeStatus.Deleted)
                    .update();
        }
        if (oldState == VmInstanceState.VolumeMigrating && newState == VmInstanceState.Stopped && vm != null && vm.getRootVolumeUuid() != null) {
            // maybe restart mn, and we need restore from VolumeMigrating state
            SQL.New(VolumeVO.class).eq(VolumeVO_.uuid, vm.getRootVolumeUuid()).eq(VolumeVO_.status, VolumeStatus.Migrating)
                    .set(VolumeVO_.status, VolumeStatus.Ready)
                    .update();
        }
    }
}
