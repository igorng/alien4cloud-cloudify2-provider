package alien4cloud.paas.cloudify2;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import alien4cloud.ui.form.annotation.FormLabel;

import com.google.common.collect.Lists;

/**
 * Bean for cloudify configuration.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class PluginConfigurationBean {
    /** Url of the cloudify manager. */
    @FormLabel("CLOUDS.DRIVER.CLOUDIFY.CLOUDIFY_CONNECTION_CONFIGURATION")
    private CloudifyConnectionConfiguration cloudifyConnectionConfiguration = new CloudifyConnectionConfiguration();
    /** True if the undeployment must be performed synchronously, false if not. */
    @FormLabel("CLOUDS.DRIVER.CLOUDIFY.SYNCHRONOUS_DEPLOYMENT")
    private boolean synchronousDeployment = false;
    /** List of the compute templates defined in Cloudify. */
    @FormLabel("CLOUDS.DRIVER.CLOUDIFY.COMPUTE_TEMPLATES")
    private List<ComputeTemplate> computeTemplates = Lists.newArrayList(
            new ComputeTemplate("MEDIUM_LINUX", 1, 1000, 2048, "x86_64", "linux", "ubuntu", "ubuntu", null),
            new ComputeTemplate("CENTOS", 1, 1000, 2048, "x86_64", "linux", "centos", "centos", null),
            new ComputeTemplate("POSTGRES", 1, 1000, 2048, "x86_64", "linux", "centos", "postgre", null),
            new ComputeTemplate("OPENVPN", 1, 1000, 2048, "x86_64", "linux", "centos", "openvpn", null));
    /** List of the storage templates defined in Cloudify. */
    @FormLabel("CLOUDS.DRIVER.CLOUDIFY.STORAGE_TEMPLATES")
    private List<StorageTemplate> storageTemplates = Lists.newArrayList(
            new StorageTemplate("SMALL_BLOCK", 1, "ext4"),
            new StorageTemplate("MEDIUM_BLOCK", 2, "ext4"));
}