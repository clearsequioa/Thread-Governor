package dev.lowcore.threadgovernor;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkApplicationInfo;
import org.lwjgl.vulkan.VkInstance;
import org.lwjgl.vulkan.VkInstanceCreateInfo;
import org.lwjgl.vulkan.VkPhysicalDevice;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties;

import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.vulkan.VK10.*;

/**
 * Lists graphics devices the same way VulkanMod does: create a throwaway Vulkan instance through LWJGL and
 * ask the driver for each physical device's name and type. Kept in its own class so a missing Vulkan
 * library only affects this class, never the rest of the mod.
 */
final class VulkanProbe {
	private VulkanProbe() {
	}

	static List<GpuDetector.Gpu> list() {
		List<GpuDetector.Gpu> out = new ArrayList<>();
		try (MemoryStack stack = MemoryStack.stackPush()) {
			VkApplicationInfo app = VkApplicationInfo.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO)
				.pApplicationName(stack.UTF8("Thread Governor device list"))
				.applicationVersion(1)
				.pEngineName(stack.UTF8("none"))
				.engineVersion(1)
				.apiVersion(VK_API_VERSION_1_0);
			VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack)
				.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
				.pApplicationInfo(app);

			PointerBuffer pInstance = stack.mallocPointer(1);
			int result = vkCreateInstance(info, null, pInstance);
			if (result != VK_SUCCESS) throw new IllegalStateException("vkCreateInstance returned " + result);
			VkInstance instance = new VkInstance(pInstance.get(0), info);
			try {
				IntBuffer count = stack.mallocInt(1);
				vkEnumeratePhysicalDevices(instance, count, null);
				PointerBuffer devices = stack.mallocPointer(count.get(0));
				vkEnumeratePhysicalDevices(instance, count, devices);
				for (int i = 0; i < devices.capacity(); i++) {
					VkPhysicalDevice device = new VkPhysicalDevice(devices.get(i), instance);
					VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.malloc(stack);
					vkGetPhysicalDeviceProperties(device, props);
					int type = props.deviceType();
					if (type != VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU && type != VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) {
						continue; // skip software / virtual devices
					}
					String name = props.deviceNameString();
					boolean integrated = type == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU;
					if (out.stream().noneMatch(g -> g.name().equals(name))) {
						out.add(new GpuDetector.Gpu(name, integrated));
					}
				}
			} finally {
				vkDestroyInstance(instance, null);
			}
		}
		return out;
	}
}
