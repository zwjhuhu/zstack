package org.zstack.kvm;

import org.zstack.core.validation.ConditionalValidation;
import org.zstack.header.core.validation.Validation;
import org.zstack.header.vm.VmBootDevice;
import org.zstack.network.securitygroup.SecurityGroupMembersTO;
import org.zstack.network.securitygroup.SecurityGroupRuleTO;

import java.util.*;

public class KVMAgentCommands {
    public enum BootDev {
        hd(VmBootDevice.HardDisk),
        cdrom(VmBootDevice.CdRom);

        private VmBootDevice device;

        BootDev(VmBootDevice dev) {
            device = dev;
        }

        public VmBootDevice toVmBootDevice() {
            return device;
        }
    }

    public static class AgentResponse implements ConditionalValidation {
        private boolean success = true;
        private String error;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
            this.success = false;
        }

        @Override
        public boolean needValidation() {
            return success;
        }
    }

    public static class AgentCommand {
        public LinkedHashMap kvmHostAddons;
    }

    public static class CheckVmStateCmd extends AgentCommand {
        public List<String> vmUuids;
        public String hostUuid;
    }

    public static class CheckVmStateRsp extends AgentResponse {
        public Map<String, String> states;
    }

    public static class DetachNicCommand extends AgentCommand {
        private String vmUuid;
        private NicTO nic;

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public NicTO getNic() {
            return nic;
        }

        public void setNic(NicTO nic) {
            this.nic = nic;
        }
    }

    public static class DetachNicRsp extends AgentResponse {

    }

    public static class AttachNicCommand extends AgentCommand {
        private String vmUuid;
        private NicTO nic;
        private Map addons = new HashMap();

        public Map getAddons() {
            return addons;
        }

        public void setAddons(Map addons) {
            this.addons = addons;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public NicTO getNic() {
            return nic;
        }

        public void setNic(NicTO nic) {
            this.nic = nic;
        }
    }

    public static class AttachNicResponse extends AgentResponse {

    }

    public static class UpdateNicCmd extends AgentCommand implements VmAddOnsCmd {
        private String vmInstanceUuid;
        private List<KVMAgentCommands.NicTO> nics;
        private Map<String, Object> addons = new HashMap<>();

        public List<NicTO> getNics() {
            return nics;
        }

        public void setNics(List<NicTO> nics) {
            this.nics = nics;
        }

        public Map<String, Object> getAddons() {
            if (addons == null) {
                addons = new HashMap<>();
            }
            return addons;
        }

        public void setAddons(Map<String, Object> addons) {
            this.addons = addons;
        }

        @Override
        public String getVmInstanceUuid() {
            return vmInstanceUuid;
        }

        public void setVmInstanceUuid(String vmInstanceUuid) {
            this.vmInstanceUuid = vmInstanceUuid;
        }
    }

    public static class UpdateNicRsp extends AgentResponse {

    }

    public static class ConnectCmd extends AgentCommand {
        private String hostUuid;
        private String sendCommandUrl;
        private List<String> iptablesRules;
        private boolean ignoreMsrs;
        private boolean pageTableExtensionDisabled;

        public boolean isIgnoreMsrs() {
            return ignoreMsrs;
        }

        public void setIgnoreMsrs(boolean ignoreMsrs) {
            this.ignoreMsrs = ignoreMsrs;
        }

        public List<String> getIptablesRules() {
            return iptablesRules;
        }

        public void setIptablesRules(List<String> iptablesRules) {
            this.iptablesRules = iptablesRules;
        }

        public String getSendCommandUrl() {
            return sendCommandUrl;
        }

        public void setSendCommandUrl(String sendCommandUrl) {
            this.sendCommandUrl = sendCommandUrl;
        }

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }

        public boolean isPageTableExtensionDisabled() {
            return pageTableExtensionDisabled;
        }

        public void setPageTableExtensionDisabled(boolean pageTableExtensionDisabled) {
            this.pageTableExtensionDisabled = pageTableExtensionDisabled;
        }
    }

    public static class ConnectResponse extends AgentResponse {
        private String libvirtVersion;
        private String qemuVersion;
        private boolean iptablesSucc;

        public boolean isIptablesSucc() {
            return iptablesSucc;
        }

        public void setIptablesSucc(boolean iptablesSucc) {
            this.iptablesSucc = iptablesSucc;
        }

        public String getLibvirtVersion() {
            return libvirtVersion;
        }

        public void setLibvirtVersion(String libvirtVersion) {
            this.libvirtVersion = libvirtVersion;
        }

        public String getQemuVersion() {
            return qemuVersion;
        }

        public void setQemuVersion(String qemuVersion) {
            this.qemuVersion = qemuVersion;
        }
    }

    public static class PingCmd extends AgentCommand {
        public String hostUuid;
    }

    public static class PingResponse extends AgentResponse {
        private String hostUuid;

        public String getHostUuid() {
            return hostUuid;
        }

        public void setHostUuid(String hostUuid) {
            this.hostUuid = hostUuid;
        }
    }

    public static class CheckPhysicalNetworkInterfaceCmd extends AgentCommand {
        private List<String> interfaceNames = new ArrayList<>(2);

        public CheckPhysicalNetworkInterfaceCmd addInterfaceName(String name) {
            interfaceNames.add(name);
            return this;
        }

        public List<String> getInterfaceNames() {
            return interfaceNames;
        }

        public void setInterfaceNames(List<String> interfaceNames) {
            this.interfaceNames = interfaceNames;
        }
    }

    public static class CheckPhysicalNetworkInterfaceResponse extends AgentResponse {
        private List<String> failedInterfaceNames;

        public List<String> getFailedInterfaceNames() {
            if (failedInterfaceNames == null) {
                failedInterfaceNames = new ArrayList<String>(0);
            }
            return failedInterfaceNames;
        }

        public void setFailedInterfaceNames(List<String> failedInterfaceNames) {
            this.failedInterfaceNames = failedInterfaceNames;
        }
    }

    public static class HostFactCmd extends AgentCommand {
    }

    public static class HostFactResponse extends AgentResponse {
        private String osDistribution;
        private String osVersion;
        private String osRelease;
        private String qemuImgVersion;
        private String libvirtVersion;
        private String hvmCpuFlag;
        private String cpuModelName;
        private String cpuGHz;
        private String hostCpuModelName;
        private String systemProductName;
        private String systemSerialNumber;
        private List<String> ipAddresses;

        public String getOsDistribution() {
            return osDistribution;
        }

        public void setOsDistribution(String osDistribution) {
            this.osDistribution = osDistribution;
        }

        public String getOsVersion() {
            return osVersion;
        }

        public void setOsVersion(String osVersion) {
            this.osVersion = osVersion;
        }

        public String getOsRelease() {
            return osRelease;
        }

        public void setOsRelease(String osRelease) {
            this.osRelease = osRelease;
        }

        public String getHvmCpuFlag() {
            return hvmCpuFlag;
        }

        public void setHvmCpuFlag(String hvmCpuFlag) {
            this.hvmCpuFlag = hvmCpuFlag;
        }

        public String getLibvirtVersion() {
            return libvirtVersion;
        }

        public void setLibvirtVersion(String libvirtVersion) {
            this.libvirtVersion = libvirtVersion;
        }

        public String getQemuImgVersion() {
            return qemuImgVersion;
        }

        public void setQemuImgVersion(String qemuImgVersion) {
            this.qemuImgVersion = qemuImgVersion;
        }

        public List<String> getIpAddresses() {
            return ipAddresses;
        }

        public void setIpAddresses(List<String> ipAddresses) {
            this.ipAddresses = ipAddresses;
        }

        public String getCpuModelName() {
            return cpuModelName;
        }

        public void setCpuModelName(String cpuModelName) {
            this.cpuModelName = cpuModelName;
        }

        public String getCpuGHz() {
            return cpuGHz;
        }

        public String getHostCpuModelName() {
            return hostCpuModelName;
        }

        public String getSystemProductName() {
            return systemProductName;
        }

        public void setSystemProductName(String systemProductName) {
            this.systemProductName = systemProductName;
        }

        public String getSystemSerialNumber() {
            return systemSerialNumber;
        }

        public void setSystemSerialNumber(String systemSerialNumber) {
            this.systemSerialNumber = systemSerialNumber;
        }
    }

    public static class HostCapacityCmd extends AgentCommand {
    }

    public static class HostCapacityResponse extends AgentResponse {
        private long cpuNum;
        private long cpuSpeed;
        private long usedCpu;
        private long totalMemory;
        private long usedMemory;
        private int cpuSockets;

        public int getCpuSockets() {
            return cpuSockets;
        }

        public void setCpuSockets(int cpuSockets) {
            this.cpuSockets = cpuSockets;
        }

        public long getCpuNum() {
            return cpuNum;
        }

        public void setCpuNum(long cpuNum) {
            this.cpuNum = cpuNum;
        }

        public long getCpuSpeed() {
            return cpuSpeed;
        }

        public void setCpuSpeed(long cpuSpeed) {
            this.cpuSpeed = cpuSpeed;
        }

        public long getUsedCpu() {
            return usedCpu;
        }

        public void setUsedCpu(long usedCpu) {
            this.usedCpu = usedCpu;
        }

        public long getTotalMemory() {
            return totalMemory;
        }

        public void setTotalMemory(long totalMemory) {
            this.totalMemory = totalMemory;
        }

        public long getUsedMemory() {
            return usedMemory;
        }

        public void setUsedMemory(long usedMemory) {
            this.usedMemory = usedMemory;
        }
    }

    public static class CreateBridgeCmd extends AgentCommand {
        private String physicalInterfaceName;
        private String bridgeName;
        private String l2NetworkUuid;

        public String getL2NetworkUuid() {
            return l2NetworkUuid;
        }

        public void setL2NetworkUuid(String l2NetworkUuid) {
            this.l2NetworkUuid = l2NetworkUuid;
        }

        public String getPhysicalInterfaceName() {
            return physicalInterfaceName;
        }

        public void setPhysicalInterfaceName(String physicalInterfaceName) {
            this.physicalInterfaceName = physicalInterfaceName;
        }

        public String getBridgeName() {
            return bridgeName;
        }

        public void setBridgeName(String bridgeName) {
            this.bridgeName = bridgeName;
        }
    }


    public static class CreateBridgeResponse extends AgentResponse {
    }

    public static class CheckBridgeCmd extends AgentCommand {
        private String physicalInterfaceName;
        private String bridgeName;

        public String getPhysicalInterfaceName() {
            return physicalInterfaceName;
        }

        public void setPhysicalInterfaceName(String physicalInterfaceName) {
            this.physicalInterfaceName = physicalInterfaceName;
        }

        public String getBridgeName() {
            return bridgeName;
        }

        public void setBridgeName(String bridgeName) {
            this.bridgeName = bridgeName;
        }
    }

    public static class CheckBridgeResponse extends AgentResponse {
    }

    public static class CheckVlanBridgeCmd extends CheckBridgeCmd {
        private int vlan;

        public int getVlan() {
            return vlan;
        }

        public void setVlan(int vlan) {
            this.vlan = vlan;
        }
    }


    public static class CheckVlanBridgeResponse extends CheckBridgeResponse {
    }

    public static class CreateVlanBridgeCmd extends CreateBridgeCmd {
        private int vlan;

        public int getVlan() {
            return vlan;
        }

        public void setVlan(int vlan) {
            this.vlan = vlan;
        }
    }

    public static class CreateVlanBridgeResponse extends CreateBridgeResponse {
    }

    public static class NicTO {
        private String mac;
        private List<String> ips;
        private String bridgeName;
        private String uuid;
        private String nicInternalName;
        private int deviceId;
        private String metaData;
        private Boolean useVirtio;

        public List<String> getIps() {
            return ips;
        }

        public void setIps(List<String> ips) {
            this.ips = ips;
        }

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public Boolean getUseVirtio() {
            return useVirtio;
        }

        public void setUseVirtio(Boolean useVirtio) {
            this.useVirtio = useVirtio;
        }

        public String getMac() {
            return mac;
        }

        public void setMac(String mac) {
            this.mac = mac;
        }

        public String getBridgeName() {
            return bridgeName;
        }

        public void setBridgeName(String bridgeName) {
            this.bridgeName = bridgeName;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(int deviceId) {
            this.deviceId = deviceId;
        }

        public String getMetaData() {
            return metaData;
        }

        public void setMetaData(String metaData) {
            this.metaData = metaData;
        }

        public String getNicInternalName() {
            return nicInternalName;
        }

        public void setNicInternalName(String nicInternalName) {
            this.nicInternalName = nicInternalName;
        }

    }

    public static class VolumeSnapshotJobTO {
        public String vmInstanceUuid;
        public String installPath;
        public String previousInstallPath;
        public String newVolumeInstallPath;
        public String live;
        public String full;

        public VolumeSnapshotJobTO() {
        }

        public String getVmInstanceUuid() {
            return vmInstanceUuid;
        }

        public void setVmInstanceUuid(String vmInstanceUuid) {
            this.vmInstanceUuid = vmInstanceUuid;
        }

        public String getInstallPath() {
            return installPath;
        }

        public void setInstallPath(String installPath) {
            this.installPath = installPath;
        }

        public String getPreviousInstallPath() {
            return previousInstallPath;
        }

        public void setPreviousInstallPath(String previousInstallPath) {
            this.previousInstallPath = previousInstallPath;
        }

        public String getNewVolumeInstallPath() {
            return newVolumeInstallPath;
        }

        public void setNewVolumeInstallPath(String newVolumeInstallPath) {
            this.newVolumeInstallPath = newVolumeInstallPath;
        }

        public String getLive() {
            return live;
        }

        public void setLive(String live) {
            this.live = live;
        }

        public String getFull() {
            return full;
        }

        public void setFull(String full) {
            this.full = full;
        }
    }

    public static class DetachDataVolumeCmd extends AgentCommand {
        private VolumeTO volume;
        private String vmInstanceUuid;

        public VolumeTO getVolume() {
            return volume;
        }

        public void setVolume(VolumeTO volume) {
            this.volume = volume;
        }

        public String getVmUuid() {
            return vmInstanceUuid;
        }

        public void setVmUuid(String vmInstanceUuid) {
            this.vmInstanceUuid = vmInstanceUuid;
        }
    }

    public static class DetachDataVolumeResponse extends AgentResponse {
    }

    public static class AttachDataVolumeCmd extends AgentCommand {
        private VolumeTO volume;
        private String vmInstanceUuid;
        private Map<String, Object> addons;

        public Map<String, Object> getAddons() {
            if (addons == null) {
                addons = new HashMap<>();
            }
            return addons;
        }

        public void setAddons(Map<String, Object> addons) {
            this.addons = addons;
        }

        public VolumeTO getVolume() {
            return volume;
        }

        public void setVolume(VolumeTO volume) {
            this.volume = volume;
        }

        public String getVmUuid() {
            return vmInstanceUuid;
        }

        public void setVmUuid(String vmInstanceUuid) {
            this.vmInstanceUuid = vmInstanceUuid;
        }
    }

    public static class AttachDataVolumeResponse extends AgentResponse {
    }

    public static class IsoTO {
        private String path;
        private String imageUuid;
        private int deviceId;

        public IsoTO() {
        }

        public IsoTO(IsoTO other) {
            this.path = other.path;
            this.imageUuid = other.imageUuid;
            this.deviceId = other.deviceId;
        }


        public String getImageUuid() {
            return imageUuid;
        }

        public void setImageUuid(String imageUuid) {
            this.imageUuid = imageUuid;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(int deviceId) {
            this.deviceId = deviceId;
        }
    }

    public static class CdRomTO {
        private String path;
        private String imageUuid;
        private int deviceId;
        // unmounted iso
        private boolean isEmpty;

        public CdRomTO() {
        }

        public CdRomTO(CdRomTO other) {
            this.isEmpty = other.isEmpty;
            this.path = other.path;
            this.imageUuid = other.imageUuid;
            this.deviceId = other.deviceId;
        }


        public String getImageUuid() {
            return imageUuid;
        }

        public void setImageUuid(String imageUuid) {
            this.imageUuid = imageUuid;
        }

        public String getPath() {
            return path;
        }

        public void setPath(String path) {
            this.path = path;
        }

        public int getDeviceId() {
            return deviceId;
        }

        public void setDeviceId(int deviceId) {
            this.deviceId = deviceId;
        }

        public boolean isEmpty() {
            return isEmpty;
        }

        public void setEmpty(boolean empty) {
            this.isEmpty = empty;
        }
    }

    public static class HardenVmConsoleCmd extends AgentCommand {
        public String vmUuid;
        public Long vmInternalId;
        public String hostManagementIp;
    }

    public static class DeleteVmConsoleFirewallCmd extends AgentCommand {
        public String vmUuid;
        public Long vmInternalId;
        public String hostManagementIp;
    }

    public interface VmAddOnsCmd {
        Map<String, Object> getAddons();

        String getVmInstanceUuid();
    }

    public static class StartVmCmd extends AgentCommand implements VmAddOnsCmd {
        private String vmInstanceUuid;
        private long vmInternalId;
        private String vmName;
        private long memory;
        private long maxMemory;
        private int cpuNum;
        private long cpuSpeed;
        private int socketNum;
        private int cpuOnSocket;
        private String consolePassword;
        private List<String> bootDev;
        private VolumeTO rootVolume;
        private List<IsoTO> bootIso = new ArrayList<>();
        private List<CdRomTO> cdRoms = new ArrayList<>();
        private List<VolumeTO> dataVolumes;
        private List<NicTO> nics;
        private long timeout;
        private Map<String, Object> addons;
        private String consoleMode;
        private boolean instanceOfferingOnlineChange;
        private String nestedVirtualization;
        private String hostManagementIp;
        private String clock;
        private String videoType;
        private String spiceStreamingMode;
        private boolean useNuma;
        private String usbRedirect;
        private Integer VDIMonitorNumber;
        private boolean useBootMenu;
        private boolean createPaused;
        private boolean kvmHiddenState;
        private boolean vmPortOff;
        private String vmCpuModel;
        private boolean emulateHyperV;
        private boolean additionalQmp;
        private boolean isApplianceVm;
        private String systemSerialNumber;
        private String bootMode;
        private boolean fromForeignHypervisor;
        private String machineType;
        private Integer pciePortNums;
        private boolean useHugePage;

        public boolean isUseHugePage() {
            return useHugePage;
        }

        public void setUseHugePage(boolean useHugePage) {
            this.useHugePage = useHugePage;
        }

        public boolean isFromForeignHypervisor() {
            return fromForeignHypervisor;
        }

        public void setFromForeignHypervisor(boolean fromForeignHypervisor) {
            this.fromForeignHypervisor = fromForeignHypervisor;
        }

        public String getMachineType() {
            return machineType;
        }

        public void setMachineType(String machineType) {
            this.machineType = machineType;
        }

        public void setPciePortNums(Integer pciePortNums) {
            this.pciePortNums = pciePortNums;
        }

        public Integer getPciePortNums() {
            return pciePortNums;
        }

        public boolean isAdditionalQmp() {
            return additionalQmp;
        }

        public void setAdditionalQmp(boolean additionalQmp) {
            this.additionalQmp = additionalQmp;
        }

        public String getBootMode() {
            return bootMode;
        }

        public void setBootMode(String bootMode) {
            this.bootMode = bootMode;
        }

        public boolean isEmulateHyperV() {
            return emulateHyperV;
        }

        public void setEmulateHyperV(boolean emulateHyperV) {
            this.emulateHyperV = emulateHyperV;
        }

        public boolean isApplianceVm() {
            return isApplianceVm;
        }

        public void setApplianceVm(boolean applianceVm) {
            isApplianceVm = applianceVm;
        }

        public String getSystemSerialNumber() {
            return systemSerialNumber;
        }

        public void setSystemSerialNumber(String systemSerialNumber) {
            this.systemSerialNumber = systemSerialNumber;
        }

        public String getVmCpuModel() {
            return vmCpuModel;
        }

        public void setVmCpuModel(String vmCpuModel) {
            this.vmCpuModel = vmCpuModel;
        }

        public String getSpiceStreamingMode() {
            return spiceStreamingMode;
        }

        public void setSpiceStreamingMode(String spiceStreamingMode) {
            this.spiceStreamingMode = spiceStreamingMode;
        }

        public Integer getVDIMonitorNumber() {
            return VDIMonitorNumber;
        }

        public void setVDIMonitorNumber(Integer VDIMonitorNumber) {
            this.VDIMonitorNumber = VDIMonitorNumber;
        }

        public boolean isKvmHiddenState() {
            return kvmHiddenState;
        }

        public void setKvmHiddenState(boolean kvmHiddenState) {
            this.kvmHiddenState = kvmHiddenState;
        }

        public void setVmPortOff(boolean vmPortOff) {
            this.vmPortOff = vmPortOff;
        }

        public boolean isVmPortOff() {
            return vmPortOff;
        }

        public void setUseBootMenu(boolean useBootMenu) {
            this.useBootMenu = useBootMenu;
        }

        public boolean isUseBootMenu() {
            return useBootMenu;
        }

        public void setCreatePaused(boolean createPaused) {
            this.createPaused = createPaused;
        }

        public boolean isCreatePaused() {
            return createPaused;
        }

        public String getUsbRedirect() {
            return usbRedirect;
        }

        public void setUsbRedirect(String usbRedirect) {
            this.usbRedirect = usbRedirect;
        }

        public boolean isUseNuma() {
            return useNuma;
        }

        public void setUseNuma(boolean useNuma) {
            this.useNuma = useNuma;
        }

        public long getMaxMemory() {
            return maxMemory;
        }

        public void setMaxMemory(long maxMemory) {
            this.maxMemory = maxMemory;
        }

        public String getVideoType() {
            return videoType;
        }

        public void setVideoType(String videoType) {
            this.videoType = videoType;
        }

        public String getClock() {
            return clock;
        }

        public void setClock(String clock) {
            this.clock = clock;
        }

        public int getSocketNum() {
            return socketNum;
        }

        public void setSocketNum(int socketNum) {
            this.socketNum = socketNum;
        }

        public int getCpuOnSocket() {
            return cpuOnSocket;
        }

        public void setCpuOnSocket(int cpuOnSocket) {
            this.cpuOnSocket = cpuOnSocket;
        }

        public String getVmName() {
            return vmName;
        }

        public void setVmName(String vmName) {
            this.vmName = vmName;
        }

        public long getMemory() {
            return memory;
        }

        public void setMemory(long memory) {
            this.memory = memory;
        }

        public int getCpuNum() {
            return cpuNum;
        }

        public void setCpuNum(int cpuNum) {
            this.cpuNum = cpuNum;
        }

        public long getCpuSpeed() {
            return cpuSpeed;
        }

        public void setCpuSpeed(long cpuSpeed) {
            this.cpuSpeed = cpuSpeed;
        }

        public List<String> getBootDev() {
            return bootDev;
        }

        public void setBootDev(List<String> bootDev) {
            this.bootDev = bootDev;
        }

        public String getConsolePassword() {
            return consolePassword;
        }

        public void setConsolePassword(String consolePassword) {
            this.consolePassword = consolePassword;
        }

        public boolean getInstanceOfferingOnlineChange() {
            return instanceOfferingOnlineChange;
        }

        public void setInstanceOfferingOnlineChange(boolean instanceOfferingOnlineChange) {
            this.instanceOfferingOnlineChange = instanceOfferingOnlineChange;
        }

        @Deprecated
        public List<IsoTO> getBootIso() {
            return bootIso;
        }

        @Deprecated
        public void setBootIso(List<IsoTO> bootIso) {
            this.bootIso = bootIso;
        }

        public List<CdRomTO> getCdRoms() {
            return cdRoms;
        }

        public void setCdRoms(List<CdRomTO> cdRoms) {
            this.cdRoms = cdRoms;
        }

        public String getConsoleMode() {
            return consoleMode;
        }

        public void setConsoleMode(String consoleMode) {
            this.consoleMode = consoleMode;
        }

        public String getNestedVirtualization() {
            return nestedVirtualization;
        }

        public void setNestedVirtualization(String nestedVirtualization) {
            this.nestedVirtualization = nestedVirtualization;
        }

        public String getHostManagementIp() {
            return hostManagementIp;
        }

        public void setHostManagementIp(String hostManagementIp) {
            this.hostManagementIp = hostManagementIp;
        }

        public VolumeTO getRootVolume() {
            return rootVolume;
        }

        public void setRootVolume(VolumeTO rootVolume) {
            this.rootVolume = rootVolume;
        }

        public List<VolumeTO> getDataVolumes() {
            return dataVolumes;
        }

        public void setDataVolumes(List<VolumeTO> dataVolumes) {
            this.dataVolumes = dataVolumes;
        }

        public List<NicTO> getNics() {
            return nics;
        }

        public void setNics(List<NicTO> nics) {
            this.nics = nics;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        @Override
        public String getVmInstanceUuid() {
            return vmInstanceUuid;
        }

        public void setVmInstanceUuid(String vmInstanceUuid) {
            this.vmInstanceUuid = vmInstanceUuid;
        }

        public long getVmInternalId() {
            return vmInternalId;
        }

        public void setVmInternalId(long vmInternalId) {
            this.vmInternalId = vmInternalId;
        }

        @Override
        public Map<String, Object> getAddons() {
            if (addons == null) {
                addons = new HashMap<>();
            }
            return addons;
        }

        public void setAddons(Map<String, Object> addons) {
            this.addons = addons;
        }
    }

    public static class StartVmResponse extends AgentResponse {
    }

    public static class ChangeCpuMemoryCmd extends AgentCommand {
        private String vmUuid;
        private int cpuNum;
        private long memorySize;

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setCpuNum(int cpuNum) {
            this.cpuNum = cpuNum;
        }

        public int getCpuNum() {
            return cpuNum;
        }

        public void setMemorySize(long memorySize) {
            this.memorySize = memorySize;
        }

        public long getMemorySize() {
            return memorySize;
        }
    }

    public static class ChangeCpuMemoryResponse extends AgentResponse {
        private int cpuNum;
        private long memorySize;

        public void setCpuNum(int cpuNum) {
            this.cpuNum = cpuNum;
        }

        public int getCpuNum() {
            return cpuNum;
        }

        public void setMemorySize(long memorySize) {
            this.memorySize = memorySize;
        }

        public long getMemorySize() {
            return memorySize;
        }
    }

    public static class IncreaseCpuCmd extends AgentCommand {
        private String vmUuid;
        private int cpuNum;

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setCpuNum(int cpuNum) {
            this.cpuNum = cpuNum;
        }

        public int getCpuNum() {
            return cpuNum;
        }
    }

    public static class IncreaseCpuResponse extends AgentResponse {
        private int cpuNum;

        public void setCpuNum(int cpuNum) {
            this.cpuNum = cpuNum;
        }

        public int getCpuNum() {
            return cpuNum;
        }
    }

    public static class IncreaseMemoryCmd extends AgentCommand {
        private String vmUuid;
        private long memorySize;

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setMemorySize(long memorySize) {
            this.memorySize = memorySize;
        }

        public long getMemorySize() {
            return memorySize;
        }
    }

    public static class IncreaseMemoryResponse extends AgentResponse {
        private long memorySize;

        public void setMemorySize(long memorySize) {
            this.memorySize = memorySize;
        }

        public long getMemorySize() {
            return memorySize;
        }
    }

    public static class GetVncPortCmd extends AgentCommand {
        private String vmUuid;

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }
    }

    public static class GetVncPortResponse extends AgentResponse {
        private int port;
        private String protocol;

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getProtocol() {
            return protocol;
        }

        public void setProtocol(String protocol) {
            this.protocol = protocol;
        }
    }

    public static class StopVmCmd extends AgentCommand {
        private String uuid;
        private String type;
        private long timeout;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }
    }

    public static class StopVmResponse extends AgentResponse {
    }

    public static class PauseVmCmd extends AgentCommand {
        private String uuid;
        private long timeout;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    public static class PauseVmResponse extends AgentResponse {

    }

    public static class ResumeVmCmd extends AgentCommand {
        private String uuid;
        private long timeout;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    public static class ResumeVmResponse extends AgentResponse {

    }

    public static class RebootVmCmd extends AgentCommand {
        private String uuid;
        private long timeout;
        private List<String> bootDev;

        public List<String> getBootDev() {
            return bootDev;
        }

        public void setBootDev(List<String> bootDev) {
            this.bootDev = bootDev;
        }

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }

        public long getTimeout() {
            return timeout;
        }

        public void setTimeout(long timeout) {
            this.timeout = timeout;
        }
    }

    public static class RebootVmResponse extends AgentResponse {
    }

    public static class DestroyVmCmd extends AgentCommand {
        private String uuid;

        public String getUuid() {
            return uuid;
        }

        public void setUuid(String uuid) {
            this.uuid = uuid;
        }
    }

    public static class DestroyVmResponse extends AgentResponse {
    }


    public static class VmSyncCmd extends AgentCommand {
    }

    public static class VmSyncResponse extends AgentResponse {
        private HashMap<String, String> states;

        public HashMap<String, String> getStates() {
            return states;
        }

        public void setStates(HashMap<String, String> states) {
            this.states = states;
        }
    }

    public static class RefreshAllRulesOnHostCmd extends AgentCommand {
        private List<SecurityGroupRuleTO> ruleTOs;
        private List<SecurityGroupRuleTO> ipv6RuleTOs;

        public List<SecurityGroupRuleTO> getRuleTOs() {
            return ruleTOs;
        }

        public void setRuleTOs(List<SecurityGroupRuleTO> ruleTOs) {
            this.ruleTOs = ruleTOs;
        }

        public List<SecurityGroupRuleTO> getIpv6RuleTOs() {
            return ipv6RuleTOs;
        }

        public void setIpv6RuleTOs(List<SecurityGroupRuleTO> ipv6RuleTOs) {
            this.ipv6RuleTOs = ipv6RuleTOs;
        }
    }

    public static class RefreshAllRulesOnHostResponse extends AgentResponse {
    }

    public static class CheckDefaultSecurityGroupCmd extends AgentCommand {
    }

    public static class CheckDefaultSecurityGroupResponse extends AgentResponse {

    }

    public static class UpdateGroupMemberCmd extends AgentCommand {
        private List<SecurityGroupMembersTO> updateGroupTOs;

        public void setUpdateGroupTOs(List<SecurityGroupMembersTO> updateGroupTOs) {
            this.updateGroupTOs = updateGroupTOs;
        }

        public List<SecurityGroupMembersTO> getUpdateGroupTOs() {
            return updateGroupTOs;
        }
    }

    public static class UpdateGroupMemberResponse extends AgentResponse {
    }

    public static class CleanupUnusedRulesOnHostCmd extends AgentCommand {
    }

    public static class CleanupUnusedRulesOnHostResponse extends AgentResponse {
    }


    public static class ApplySecurityGroupRuleCmd extends AgentCommand {
        private List<SecurityGroupRuleTO> ruleTOs;
        private List<SecurityGroupRuleTO> ipv6RuleTOs;

        public List<SecurityGroupRuleTO> getRuleTOs() {
            return ruleTOs;
        }

        public void setRuleTOs(List<SecurityGroupRuleTO> ruleTOs) {
            this.ruleTOs = ruleTOs;
        }

        public List<SecurityGroupRuleTO> getIpv6RuleTOs() {
            return ipv6RuleTOs;
        }

        public void setIpv6RuleTOs(List<SecurityGroupRuleTO> ipv6RuleTOs) {
            this.ipv6RuleTOs = ipv6RuleTOs;
        }
    }

    public static class ApplySecurityGroupRuleResponse extends AgentResponse {
    }

    public static class MigrateVmCmd extends AgentCommand {
        private String vmUuid;
        private String destHostIp;
        private String storageMigrationPolicy;
        private String srcHostIp;
        private boolean useNuma;
        private boolean migrateFromDestination;
        private boolean autoConverge;
        private Long timeout; // in seconds

        public boolean isUseNuma() {
            return useNuma;
        }

        public void setUseNuma(boolean useNuma) {
            this.useNuma = useNuma;
        }

        public String getSrcHostIp() {
            return srcHostIp;
        }

        public void setSrcHostIp(String srcHostIp) {
            this.srcHostIp = srcHostIp;
        }

        public String getStorageMigrationPolicy() {
            return storageMigrationPolicy;
        }

        public void setStorageMigrationPolicy(String storageMigrationPolicy) {
            this.storageMigrationPolicy = storageMigrationPolicy;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getDestHostIp() {
            return destHostIp;
        }

        public void setDestHostIp(String destHostIp) {
            this.destHostIp = destHostIp;
        }

        public boolean isAutoConverge() {
            return autoConverge;
        }

        public void setAutoConverge(boolean autoConverge) {
            this.autoConverge = autoConverge;
        }

        public boolean isMigrateFromDestination() {
            return migrateFromDestination;
        }

        public void setMigrateFromDestination(boolean migrateFromDestination) {
            this.migrateFromDestination = migrateFromDestination;
        }

        public Long getTimeout() {
            return timeout;
        }

        public void setTimeout(Long timeout) {
            this.timeout = timeout;
        }
    }

    public static class MigrateVmResponse extends AgentResponse {
    }

    public static class MergeSnapshotRsp extends AgentResponse {
    }

    public static class MergeSnapshotCmd extends AgentCommand {
        private String vmUuid;
        private VolumeTO volume;
        private String srcPath;
        private String destPath;
        private boolean fullRebase;

        public boolean isFullRebase() {
            return fullRebase;
        }

        public void setFullRebase(boolean fullRebase) {
            this.fullRebase = fullRebase;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getSrcPath() {
            return srcPath;
        }

        public void setSrcPath(String srcPath) {
            this.srcPath = srcPath;
        }

        public String getDestPath() {
            return destPath;
        }

        public void setDestPath(String destPath) {
            this.destPath = destPath;
        }

        public VolumeTO getVolume() {
            return volume;
        }

        public void setVolume(VolumeTO volume) {
            this.volume = volume;
        }
    }

    public static class TakeSnapshotCmd extends AgentCommand {
        private String vmUuid;
        private String volumeUuid;
        private VolumeTO volume;
        private String installPath;
        private boolean fullSnapshot;
        private String volumeInstallPath;
        private String newVolumeUuid;
        private String newVolumeInstallPath;

        public String getVolumeUuid() {
            return volumeUuid;
        }

        public void setVolumeUuid(String volumeUuid) {
            this.volumeUuid = volumeUuid;
        }

        public String getVolumeInstallPath() {
            return volumeInstallPath;
        }

        public void setVolumeInstallPath(String volumeInstallPath) {
            this.volumeInstallPath = volumeInstallPath;
        }

        public boolean isFullSnapshot() {
            return fullSnapshot;
        }

        public void setFullSnapshot(boolean fullSnapshot) {
            this.fullSnapshot = fullSnapshot;
        }

        public String getVmUuid() {
            return vmUuid;
        }

        public void setVmUuid(String vmUuid) {
            this.vmUuid = vmUuid;
        }

        public String getInstallPath() {
            return installPath;
        }

        public void setInstallPath(String installPath) {
            this.installPath = installPath;
        }

        public String getNewVolumeInstallPath() {
            return newVolumeInstallPath;
        }

        public void setNewVolumeInstallPath(String newVolumeInstallPath) {
            this.newVolumeInstallPath = newVolumeInstallPath;
        }

        public String getNewVolumeUuid() {
            return newVolumeUuid;
        }

        public void setNewVolumeUuid(String newVolumeUuid) {
            this.newVolumeUuid = newVolumeUuid;
        }

        public VolumeTO getVolume() {
            return volume;
        }

        public void setVolume(VolumeTO volume) {
            this.volume = volume;
        }
    }

    public static class TakeSnapshotResponse extends AgentResponse {
        @Validation
        private String newVolumeInstallPath;
        @Validation
        private String snapshotInstallPath;
        @Validation(notZero = true)
        private long size;

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public String getSnapshotInstallPath() {
            return snapshotInstallPath;
        }

        public void setSnapshotInstallPath(String snapshotInstallPath) {
            this.snapshotInstallPath = snapshotInstallPath;
        }

        public String getNewVolumeInstallPath() {
            return newVolumeInstallPath;
        }

        public void setNewVolumeInstallPath(String newVolumeInstallPath) {
            this.newVolumeInstallPath = newVolumeInstallPath;
        }
    }

    public static class LogoutIscsiTargetCmd extends AgentCommand {
        private String hostname;
        private int port;
        private String target;

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    public static class LogoutIscsiTargetRsp extends AgentResponse {
    }

    public static class LoginIscsiTargetCmd extends AgentCommand {
        private String hostname;
        private int port;
        private String target;
        private String chapUsername;
        private String chapPassword;

        public String getChapUsername() {
            return chapUsername;
        }

        public void setChapUsername(String chapUsername) {
            this.chapUsername = chapUsername;
        }

        public String getChapPassword() {
            return chapPassword;
        }

        public void setChapPassword(String chapPassword) {
            this.chapPassword = chapPassword;
        }

        public String getHostname() {
            return hostname;
        }

        public void setHostname(String hostname) {
            this.hostname = hostname;
        }

        public int getPort() {
            return port;
        }

        public void setPort(int port) {
            this.port = port;
        }

        public String getTarget() {
            return target;
        }

        public void setTarget(String target) {
            this.target = target;
        }
    }

    public static class LoginIscsiTargetRsp extends AgentResponse {
    }

    public static class AttachIsoCmd extends AgentCommand {
        public IsoTO iso;
        public String vmUuid;
    }

    public static class AttachIsoRsp extends AgentResponse {
    }

    public static class DetachIsoCmd extends AgentCommand {
        public String vmUuid;
        public String isoUuid;
        public int deviceId;
    }

    public static class DetachIsoRsp extends AgentResponse {

    }

    public static class UpdateHostOSCmd extends AgentCommand {
        public String hostUuid;
        public String excludePackages;
    }

    public static class UpdateHostOSRsp extends AgentResponse {
    }

    public static class UpdateDependencyCmd extends AgentCommand {
        public String hostUuid;
    }

    public static class UpdateDependencyRsp extends AgentResponse {
    }

    public static class ReportVmStateCmd {
        public String hostUuid;
        public String vmUuid;
        public String vmState;
    }

    public static class ReconnectMeCmd {
        public String hostUuid;
        public String reason;
    }

    public static class ReportPsStatusCmd {
        public String hostUuid;
        public List<String> psUuids;
        public String psStatus;
        public String reason;
    }

    public static class ReportSelfFencerCmd {
        public String hostUuid;
        public List<String> psUuids;
        public String reason;
        public String vmUuidsString;
    }

    public static class ReportHostDeviceEventCmd {
        public String hostUuid;
    }

    public static class ReportVmShutdownEventCmd {
        public String vmUuid;
    }
}
