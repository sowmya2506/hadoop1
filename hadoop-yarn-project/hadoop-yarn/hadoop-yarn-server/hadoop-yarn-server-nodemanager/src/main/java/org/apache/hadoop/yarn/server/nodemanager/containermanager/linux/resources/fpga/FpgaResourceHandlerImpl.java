/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */


package org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.fpga;

import static org.apache.hadoop.yarn.api.records.ResourceInformation.FPGA_URI;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.hadoop.classification.InterfaceAudience;
import org.apache.hadoop.classification.InterfaceStability;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.util.StringUtils;
import org.apache.hadoop.yarn.api.records.ContainerId;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.nodemanager.Context;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.container.Container;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.privileged.PrivilegedOperation;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.privileged.PrivilegedOperationException;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.privileged.PrivilegedOperationExecutor;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.CGroupsHandler;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandler;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.linux.resources.ResourceHandlerException;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.fpga.FpgaDevice;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.fpga.AbstractFpgaVendorPlugin;
import org.apache.hadoop.yarn.server.nodemanager.containermanager.resourceplugin.fpga.FpgaDiscoverer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.apache.hadoop.classification.VisibleForTesting;

@InterfaceStability.Unstable
@InterfaceAudience.Private
public class FpgaResourceHandlerImpl implements ResourceHandler {
  private static final Logger LOG =
      LoggerFactory.getLogger(FpgaResourceHandlerImpl.class);

  private final String REQUEST_FPGA_IP_ID_KEY = "REQUESTED_FPGA_IP_ID";

  private final AbstractFpgaVendorPlugin vendorPlugin;

  private final FpgaResourceAllocator allocator;

  private final CGroupsHandler cGroupsHandler;

  private final FpgaDiscoverer fpgaDiscoverer;

  public static final String EXCLUDED_FPGAS_CLI_OPTION = "--excluded_fpgas";
  public static final String CONTAINER_ID_CLI_OPTION = "--container_id";
  private PrivilegedOperationExecutor privilegedOperationExecutor;

  @VisibleForTesting
  public FpgaResourceHandlerImpl(Context nmContext,
      CGroupsHandler cGroupsHandler,
      PrivilegedOperationExecutor privilegedOperationExecutor,
      AbstractFpgaVendorPlugin plugin,
      FpgaDiscoverer fpgaDiscoverer) {
    this.allocator = new FpgaResourceAllocator(nmContext);
    this.vendorPlugin = plugin;
    this.fpgaDiscoverer = fpgaDiscoverer;
    this.cGroupsHandler = cGroupsHandler;
    this.privilegedOperationExecutor = privilegedOperationExecutor;
  }

  @VisibleForTesting
  FpgaResourceAllocator getFpgaAllocator() {
    return allocator;
  }

  public String getRequestedIPID(Container container) {
    return container.getLaunchContext().getEnvironment().
        get(REQUEST_FPGA_IP_ID_KEY);
  }

  @Override
  public List<PrivilegedOperation> bootstrap(Configuration configuration)
      throws ResourceHandlerException {
    // The plugin should be initilized by FpgaDiscoverer already
    if (!vendorPlugin.initPlugin(configuration)) {
      throw new ResourceHandlerException("FPGA plugin initialization failed");
    }
    LOG.info("FPGA Plugin bootstrap success.");
    // Get avialable devices minor numbers from toolchain or static configuration
    List<FpgaDevice> fpgaDeviceList = fpgaDiscoverer.discover();
    allocator.addFpgaDevices(vendorPlugin.getFpgaType(), fpgaDeviceList);
    this.cGroupsHandler.initializeCGroupController(
        CGroupsHandler.CGroupController.DEVICES);
    return null;
  }

  @Override
  public List<PrivilegedOperation> preStart(Container container)
      throws ResourceHandlerException {
    // 1. Get requested FPGA type and count, choose corresponding FPGA plugin(s)
    // 2. Use allocator.assignFpga(type, count) to get FPGAAllocation
    // 3. If required, download to ensure IP file exists and configure IP file for all devices
    List<PrivilegedOperation> ret = new ArrayList<>();
    String containerIdStr = container.getContainerId().toString();
    Resource requestedResource = container.getResource();

    // Create device cgroups for the container
    cGroupsHandler.createCGroup(CGroupsHandler.CGroupController.DEVICES,
      containerIdStr);

    long deviceCount = requestedResource.getResourceValue(FPGA_URI);
    LOG.info(containerIdStr + " requested " + deviceCount + " Intel FPGA(s)");
    String ipFilePath = null;
    try {

      // allocate even request 0 FPGA because we need to deny all device numbers for this container
      final String requestedIPID = getRequestedIPID(container);
      String localizedIPIDHash = null;
      ipFilePath = vendorPlugin.retrieveIPfilePath(
          requestedIPID, container.getWorkDir(),
          container.getResourceSet().getLocalizedResources());
      if (ipFilePath != null) {
        try (FileInputStream fis = new FileInputStream(ipFilePath)) {
          localizedIPIDHash = DigestUtils.sha256Hex(fis);
        } catch (IOException e) {
          throw new ResourceHandlerException("Could not calculate SHA-256", e);
        }
      }

      FpgaResourceAllocator.FpgaAllocation allocation = allocator.assignFpga(
          vendorPlugin.getFpgaType(), deviceCount,
          container, localizedIPIDHash);
      LOG.info("FpgaAllocation:" + allocation);

      PrivilegedOperation privilegedOperation =
          new PrivilegedOperation(PrivilegedOperation.OperationType.FPGA,
          Arrays.asList(CONTAINER_ID_CLI_OPTION, containerIdStr));
      if (!allocation.getDenied().isEmpty()) {
        List<Integer> denied = new ArrayList<>();
        allocation.getDenied().forEach(device -> denied.add(device.getMinor()));
        privilegedOperation.appendArgs(Arrays.asList(EXCLUDED_FPGAS_CLI_OPTION,
            StringUtils.join(",", denied)));
      }
      privilegedOperationExecutor.executePrivilegedOperation(
          privilegedOperation, true);

      if (deviceCount > 0) {
        /**
         * We only support flashing one IP for all devices now. If user don't set this
         * environment variable, we assume that user's application can find the IP file by
         * itself.
         * Note that the IP downloading and reprogramming in advance in YARN is not necessary because
         * the OpenCL application may find the IP file and reprogram device on the fly. But YARN do this
         * for the containers will achieve the quickest reprogram path
         *
         * For instance, REQUESTED_FPGA_IP_ID = "matrix_mul" will make all devices
         * programmed with matrix multiplication IP
         *
         * In the future, we may support "matrix_mul:1,gzip:2" format to support different IP
         * for different devices
         *
         * */
        ipFilePath = vendorPlugin.retrieveIPfilePath(
            getRequestedIPID(container),
            container.getWorkDir(),
            container.getResourceSet().getLocalizedResources());
        if (ipFilePath == null) {
          LOG.warn("FPGA plugin failed to downloaded IP, please check the" +
              " value of environment viable: " + REQUEST_FPGA_IP_ID_KEY +
              " if you want YARN to program the device");
        } else {
          LOG.info("IP file path:" + ipFilePath);
          List<FpgaDevice> allowed = allocation.getAllowed();
          String majorMinorNumber;
          for (int i = 0; i < allowed.size(); i++) {
            FpgaDevice device = allowed.get(i);
            majorMinorNumber = device.getMajor() + ":" + device.getMinor();
            String currentHash = allowed.get(i).getAocxHash();
            if (currentHash != null &&
                currentHash.equalsIgnoreCase(localizedIPIDHash)) {
              LOG.info("IP already in device \""
                  + allowed.get(i).getAliasDevName() + "," +
                  majorMinorNumber + "\", skip reprogramming");
              continue;
            }
            if (vendorPlugin.configureIP(ipFilePath, device)) {
              // update the allocator that we update an IP of a device
              allocator.updateFpga(containerIdStr, allowed.get(i),
                  requestedIPID, localizedIPIDHash);
              //TODO: update the node constraint label
            }
          }
        }
      }
    } catch (ResourceHandlerException re) {
      allocator.cleanupAssignFpgas(containerIdStr);
      cGroupsHandler.deleteCGroup(CGroupsHandler.CGroupController.DEVICES,
          containerIdStr);
      throw re;
    } catch (PrivilegedOperationException e) {
      allocator.cleanupAssignFpgas(containerIdStr);
      cGroupsHandler.deleteCGroup(CGroupsHandler.CGroupController.DEVICES,
          containerIdStr);
      LOG.warn("Could not update cgroup for container", e);
      throw new ResourceHandlerException(e);
    }
    //isolation operation
    ret.add(new PrivilegedOperation(
        PrivilegedOperation.OperationType.ADD_PID_TO_CGROUP,
        PrivilegedOperation.CGROUP_ARG_PREFIX
        + cGroupsHandler.getPathForCGroupTasks(
        CGroupsHandler.CGroupController.DEVICES, containerIdStr)));
    return ret;
  }

  @Override
  public List<PrivilegedOperation> reacquireContainer(ContainerId containerId)
      throws ResourceHandlerException {
    allocator.recoverAssignedFpgas(containerId);
    return null;
  }

  @Override
  public List<PrivilegedOperation> updateContainer(Container container)
      throws ResourceHandlerException {
    return null;
  }

  @Override
  public List<PrivilegedOperation> postComplete(ContainerId containerId)
      throws ResourceHandlerException {
    allocator.cleanupAssignFpgas(containerId.toString());
    cGroupsHandler.deleteCGroup(CGroupsHandler.CGroupController.DEVICES,
        containerId.toString());
    return null;
  }

  @Override
  public List<PrivilegedOperation> teardown() throws ResourceHandlerException {
    return null;
  }

  @Override
  public String toString() {
    return FpgaResourceHandlerImpl.class.getName() + "{" +
        "vendorPlugin=" + vendorPlugin +
        ", allocator=" + allocator +
        '}';
  }
}
