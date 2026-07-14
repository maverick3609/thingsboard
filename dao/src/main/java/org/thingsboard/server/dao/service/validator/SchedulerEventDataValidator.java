/**
 * Copyright © 2016-2026 The Inferrix Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.thingsboard.server.dao.service.validator;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.thingsboard.common.util.JacksonUtil;
import org.thingsboard.server.common.data.Customer;
import org.thingsboard.server.common.data.Device;
import org.thingsboard.server.common.data.DeviceProfile;
import org.thingsboard.server.common.data.EntityType;
import org.thingsboard.server.common.data.OtaPackage;
import org.thingsboard.server.common.data.StringUtils;
import org.thingsboard.server.common.data.id.CustomerId;
import org.thingsboard.server.common.data.id.DeviceId;
import org.thingsboard.server.common.data.id.DeviceProfileId;
import org.thingsboard.server.common.data.id.EntityId;
import org.thingsboard.server.common.data.id.OtaPackageId;
import org.thingsboard.server.common.data.id.TenantId;
import org.thingsboard.server.common.data.ota.OtaPackageType;
import org.thingsboard.server.common.data.scheduler.SchedulerEvent;
import org.thingsboard.server.common.data.scheduler.SchedulerRepeat;
import org.thingsboard.server.common.data.scheduler.TimerRepeat;
import org.thingsboard.server.dao.customer.CustomerDao;
import org.thingsboard.server.dao.device.DeviceProfileService;
import org.thingsboard.server.dao.device.DeviceService;
import org.thingsboard.server.dao.model.ModelConstants;
import org.thingsboard.server.dao.ota.OtaPackageService;
import org.thingsboard.server.dao.scheduler.BaseSchedulerEventService;
import org.thingsboard.server.dao.service.DataValidator;
import org.thingsboard.server.dao.tenant.TenantService;
import org.thingsboard.server.exception.DataValidationException;

@Component
@Slf4j
public class SchedulerEventDataValidator extends DataValidator<SchedulerEvent> {

    @Value("${scheduler.min_interval:60}")
    private int minTimerBasedIntervalForEventInSec;

    @Autowired
    private OtaPackageService otaPackageService;
    @Autowired
    private DeviceService deviceService;
    @Autowired
    private DeviceProfileService deviceProfileService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private CustomerDao customerDao;

    @Override
    protected void validateCreate(TenantId tenantId, SchedulerEvent data) {
        validateNumberOfEntitiesPerTenant(tenantId, EntityType.SCHEDULER_EVENT);
    }

    @Override
    protected void validateDataImpl(TenantId tenantId, SchedulerEvent schedulerEvent) {
        if (StringUtils.isEmpty(schedulerEvent.getType())) {
            log.warn("SchedulerEvent type should be specified, tenantId [{}]", tenantId);
            throw new DataValidationException("SchedulerEvent type should be specified!");
        }
        if (StringUtils.isEmpty(schedulerEvent.getName())) {
            log.warn("SchedulerEvent name should be specified, tenantId [{}]", tenantId);
            throw new DataValidationException("SchedulerEvent name should be specified!");
        }
        if (schedulerEvent.getSchedule() == null) {
            log.warn("SchedulerEvent schedule configuration should be specified, tenantId [{}], name [{}]", tenantId, schedulerEvent.getName());
            throw new DataValidationException("SchedulerEvent schedule configuration should be specified!");
        }
        if (schedulerEvent.getConfiguration() == null) {
            log.warn("SchedulerEvent configuration should be specified, tenantId [{}], name [{}]", tenantId, schedulerEvent.getName());
            throw new DataValidationException("SchedulerEvent configuration should be specified!");
        }
        if (schedulerEvent.getTenantId() == null) {
            log.warn("SchedulerEvent should be assigned to tenant, name [{}]", schedulerEvent.getName());
            throw new DataValidationException("SchedulerEvent should be assigned to tenant!");
        }
        if (!tenantService.tenantExists(schedulerEvent.getTenantId())) {
            log.warn("SchedulerEvent is referencing to non-existent tenant [{}]", tenantId);
            throw new DataValidationException("SchedulerEvent is referencing to non-existent tenant!");
        }
        if (schedulerEvent.getCustomerId() == null) {
            schedulerEvent.setCustomerId(new CustomerId(ModelConstants.NULL_UUID));
        } else if (!schedulerEvent.getCustomerId().getId().equals(ModelConstants.NULL_UUID)) {
            Customer customer = customerDao.findById(tenantId, schedulerEvent.getCustomerId().getId());
            if (customer == null) {
                log.warn("Can't assign schedulerEvent to non-existent customer, tenantId [{}], customerId [{}]", tenantId, schedulerEvent.getCustomerId());
                throw new DataValidationException("Can't assign schedulerEvent to non-existent customer!");
            }
            if (!customer.getTenantId().equals(schedulerEvent.getTenantId())) {
                log.warn("Can't assign schedulerEvent to customer from different tenant, tenantId [{}], customerId [{}]", tenantId, schedulerEvent.getCustomerId());
                throw new DataValidationException("Can't assign schedulerEvent to customer from different tenant!");
            }
        }
        boolean isFirmwareUpdate = "updateFirmware".equals(schedulerEvent.getType());
        boolean isSoftwareUpdate = "updateSoftware".equals(schedulerEvent.getType());
        if (isFirmwareUpdate || isSoftwareUpdate) {
            OtaPackageId firmwareId = JacksonUtil.convertValue(schedulerEvent.getConfiguration().get("msgBody"), OtaPackageId.class);
            if (firmwareId == null) {
                log.warn("SchedulerEvent firmwareId should be specified, tenantId [{}], name [{}]", tenantId, schedulerEvent.getName());
                throw new DataValidationException("SchedulerEvent firmwareId should be specified!");
            }
            OtaPackage firmwareInfo = otaPackageService.findOtaPackageById(tenantId, firmwareId);
            if (firmwareInfo == null) {
                log.warn("Can't assign non-existent firmware, tenantId [{}], firmwareId [{}]", tenantId, firmwareId);
                throw new DataValidationException("Can't assign non-existent firmware!");
            }
            if (isFirmwareUpdate && !OtaPackageType.FIRMWARE.equals(firmwareInfo.getType())
                    || isSoftwareUpdate && !OtaPackageType.SOFTWARE.equals(firmwareInfo.getType())) {
                log.warn("SchedulerEvent can't assign firmware with different type, tenantId [{}], firmwareId [{}]", tenantId, firmwareId);
                throw new DataValidationException("SchedulerEvent Can't assign firmware with different type!");
            }
            EntityId originatorId = BaseSchedulerEventService.getOriginatorId(schedulerEvent);
            if (originatorId == null) {
                log.warn("SchedulerEvent originatorId should be specified, tenantId [{}], name [{}]", tenantId, schedulerEvent.getName());
                throw new DataValidationException("SchedulerEvent originatorId should be specified!");
            }
            switch (originatorId.getEntityType()) {
                case DEVICE -> {
                    Device device = deviceService.findDeviceById(tenantId, (DeviceId) originatorId);
                    if (!device.getDeviceProfileId().equals(firmwareInfo.getDeviceProfileId())) {
                        log.warn("SchedulerEvent can't assign firmware with different deviceProfile, tenantId [{}], deviceId [{}]", tenantId, originatorId);
                        throw new DataValidationException("SchedulerEvent can't assign firmware with different deviceProfile!");
                    }
                }
                case DEVICE_PROFILE -> {
                    DeviceProfile deviceProfile = deviceProfileService.findDeviceProfileById(tenantId, (DeviceProfileId) originatorId);
                    if (!deviceProfile.getId().equals(firmwareInfo.getDeviceProfileId())) {
                        log.warn("SchedulerEvent can't assign firmware with different deviceProfile, tenantId [{}], deviceProfileId [{}]", tenantId, originatorId);
                        throw new DataValidationException("SchedulerEvent can't assign firmware with different deviceProfile!");
                    }
                }
                default -> {
                }
            }
        }
        JsonNode repeatNode = schedulerEvent.getSchedule().get("repeat");
        if (repeatNode != null) {
            SchedulerRepeat repeat = JacksonUtil.treeToValue(repeatNode, SchedulerRepeat.class);
            if (repeat instanceof TimerRepeat timerRepeat) {
                long seconds = timerRepeat.getTimeUnit().toSeconds(timerRepeat.getRepeatInterval());
                if (seconds < minTimerBasedIntervalForEventInSec) {
                    log.warn("Timer-based repeats are too frequent, tenantId [{}], name [{}], intervalSec [{}]", tenantId, schedulerEvent.getName(), seconds);
                    throw new DataValidationException("Timer-based repeats are too frequent (less than " + minTimerBasedIntervalForEventInSec + " seconds)!");
                }
            }
        }
    }

}
