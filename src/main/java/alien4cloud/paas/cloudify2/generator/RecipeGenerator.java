package alien4cloud.paas.cloudify2.generator;

import static alien4cloud.paas.cloudify2.generator.RecipeGeneratorConstants.*;
import static alien4cloud.paas.plan.PlanGeneratorConstants.NODE_LIFECYCLE_INTERFACE_NAME;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import javax.annotation.PostConstruct;
import javax.annotation.Resource;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;

import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.cloudifysource.dsl.internal.DSLUtils;
import org.springframework.beans.factory.annotation.Required;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;

import alien4cloud.component.model.IndexedToscaElement;
import alien4cloud.model.cloud.ComputeTemplate;
import alien4cloud.model.cloud.Network;
import alien4cloud.paas.cloudify2.VelocityUtil;
import alien4cloud.paas.cloudify2.matcher.PaaSResourceMatcher;
import alien4cloud.paas.cloudify2.matcher.StorageTemplateMatcher;
import alien4cloud.paas.exception.PaaSDeploymentException;
import alien4cloud.paas.exception.PaaSTechnicalException;
import alien4cloud.paas.exception.ResourceMatchingFailedException;
import alien4cloud.paas.model.PaaSNodeTemplate;
import alien4cloud.paas.model.PaaSRelationshipTemplate;
import alien4cloud.paas.plan.OperationCallActivity;
import alien4cloud.paas.plan.PaaSPlanGenerator;
import alien4cloud.paas.plan.ParallelGateway;
import alien4cloud.paas.plan.ParallelJoinStateGateway;
import alien4cloud.paas.plan.PlanGeneratorConstants;
import alien4cloud.paas.plan.StartEvent;
import alien4cloud.paas.plan.StateUpdateEvent;
import alien4cloud.paas.plan.StopEvent;
import alien4cloud.paas.plan.WorkflowStep;
import alien4cloud.tosca.ToscaUtils;
import alien4cloud.tosca.container.model.NormativeBlockStorageConstants;
import alien4cloud.tosca.container.model.NormativeComputeConstants;
import alien4cloud.tosca.container.model.topology.ScalingPolicy;
import alien4cloud.tosca.model.ImplementationArtifact;
import alien4cloud.tosca.model.Interface;
import alien4cloud.tosca.model.Operation;
import alien4cloud.utils.FileUtil;
import alien4cloud.utils.MapUtil;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;

/**
 * Utility class that generates a cloudify recipe from a TOSCA topology.
 */
@Slf4j
@Component
@Scope(BeanDefinition.SCOPE_PROTOTYPE)
public class RecipeGenerator {
    private static final int DEFAULT_INIT_MIN_INSTANCE = 1;
    private static final int DEFAULT_MAX_INSTANCE = 1;

    private static final String DEFAULT_BLOCKSTORAGE_DEVICE = "/dev/vdb";
    private static final String DEFAULT_BLOCKSTORAGE_PATH = "/mountedStorage";
    private static final String DEFAULT_BLOCKSTORAGE_FS = "ext4";
    private static final String VOLUME_ID_VAR = "volumeId";

    private static final String SHELL_ARTIFACT_TYPE = "tosca.artifacts.ShellScript";
    private static final String GROOVY_ARTIFACT_TYPE = "tosca.artifacts.GroovyScript";

    private static final String NODE_CUSTOM_INTERFACE_NAME = "custom";
    private static final String NODE_CLOUDIFY_EXTENSIONS_INTERFACE_NAME = "fastconnect.cloudify.extensions";
    private static final String CLOUDIFY_EXTENSIONS_START_DETECTION_OPERATION_NAME = "start_detection";
    private static final String CLOUDIFY_EXTENSIONS_STOP_DETECTION_OPERATION_NAME = "stop_detection";
    private static final String CLOUDIFY_EXTENSIONS_LOCATOR_OPERATION_NAME = "locator";

    private static final String START_DETECTION_SCRIPT_FILE_NAME = "startDetection";
    private static final String STOP_DETECTION_SCRIPT_FILE_NAME = "stopDetection";
    private static final String INIT_STORAGE_SCRIPT_FILE_NAME = "initStorage";

    private String STORAGE_STARTUP_FILE_NAME = "startupBlockStorage";
    private String DEFAULT_STORAGE_CREATE_FILE_NAME = "createAttachStorage";
    private String DEFAULT_STORAGE_MOUNT_FILE_NAME = "formatMountStorage";
    private String DEFAULT_STORAGE_UNMOUNT_FILE_NAME = "unmountDeleteStorage";
    private String STORAGE_SHUTDOWN_FILE_NAME = "shutdownBlockStorage";

    private Path recipeDirectoryPath;
    private ObjectMapper jsonMapper;

    @Resource
    @Getter
    private PaaSResourceMatcher paaSResourceMatcher;
    @Resource
    @Getter
    private StorageTemplateMatcher storageTemplateMatcher;
    @Resource
    private CloudifyCommandGenerator cloudifyCommandGen;
    @Resource
    private RecipeGeneratorArtifactCopier artifactCopier;
    @Resource
    private RecipePropertiesGenerator recipePropertiesGenerator;

    private Path applicationDescriptorPath;
    private Path serviceDescriptorPath;
    private Path scriptDescriptorPath;
    private Path detectionScriptDescriptorPath;
    private Path createAttachBlockStorageScriptDescriptorPath;
    private Path formatMountBlockStorageScriptDescriptorPath;
    private Path startupBlockStorageScriptDescriptorPath;
    private Path unmountDeleteBlockStorageSCriptDescriptorPath;
    private Path shutdownBlockStorageScriptDescriptorPath;
    private Path initStorageScriptDescriptorPath;

    @PostConstruct
    public void initialize() throws IOException {
        if (!Files.exists(recipeDirectoryPath)) {
            Files.createDirectories(recipeDirectoryPath);
        } else {
            FileUtil.delete(recipeDirectoryPath);
            Files.createDirectories(recipeDirectoryPath);
        }

        // initialize velocity template paths
        applicationDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/ApplicationDescriptor.vm");
        serviceDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/ServiceDescriptor.vm");
        scriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/ScriptDescriptor.vm");
        detectionScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/detectionScriptDescriptor.vm");
        startupBlockStorageScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/startupBlockStorage.vm");
        createAttachBlockStorageScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/CreateAttachStorage.vm");
        formatMountBlockStorageScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/FormatMountStorage.vm");
        unmountDeleteBlockStorageSCriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/UnmountDeleteStorage.vm");
        shutdownBlockStorageScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/shutdownBlockStorage.vm");
        initStorageScriptDescriptorPath = recipePropertiesGenerator.loadResourceFromClasspath("classpath:velocity/initStorage.vm");

        jsonMapper = new ObjectMapper();
    }

    public Path generateRecipe(final String deploymentName, final String topologyId, final Map<String, PaaSNodeTemplate> nodeTemplates,
            final List<PaaSNodeTemplate> roots, Map<String, ComputeTemplate> cloudResourcesMapping, Map<String, Network> networkMapping) throws IOException {
        // cleanup/create the topology recipe directory
        Path recipePath = cleanupDirectory(topologyId);
        List<String> serviceIds = Lists.newArrayList();

        for (PaaSNodeTemplate root : roots) {
            String nodeName = root.getId();
            ComputeTemplate template = getComputeTemplateOrDie(cloudResourcesMapping, root);
            Network network = null;
            PaaSNodeTemplate networkNode = root.getNetworkNode();
            if (networkNode != null) {
                network = getNetworkTemplateOrDie(networkMapping, networkNode);
            }
            String serviceId = serviceIdFromNodeTemplateId(nodeName);
            generateService(nodeTemplates, recipePath, serviceId, root, template, network);
            serviceIds.add(serviceId);
        }

        generateApplicationDescriptor(recipePath, topologyId, deploymentName, serviceIds);

        return createZip(recipePath);
    }

    private Network getNetworkTemplateOrDie(Map<String, Network> networkMapping, PaaSNodeTemplate networkNode) {
        paaSResourceMatcher.verifyNetworkNode(networkNode);
        Network network = networkMapping.get(networkNode.getId());
        if (network != null) {
            return network;
        }
        throw new ResourceMatchingFailedException("Failed to find a network for node <" + networkNode.getId() + ">");
    }

    private ComputeTemplate getComputeTemplateOrDie(Map<String, ComputeTemplate> cloudResourcesMapping, PaaSNodeTemplate node) {
        paaSResourceMatcher.verifyNode(node);
        ComputeTemplate template = cloudResourcesMapping.get(node.getId());
        if (template != null) {
            return template;
        }
        throw new ResourceMatchingFailedException("Failed to find a compute template for node <" + node.getId() + ">");
    }

    public static String serviceIdFromNodeTemplateId(final String nodeTemplateId) {
        return nodeTemplateId.toLowerCase().replaceAll(" ", "-");
    }

    /**
     * Find the name of the cloudify service that host the given node template.
     *
     * @param paaSNodeTemplate The node template for which to get the service name.
     * @return The id of the service that contains the node template.
     *
     * @throws PaaSDeploymentException if the node is not declared as hosted on a compute
     */
    private String serviceIdFromNodeTemplateOrDie(final PaaSNodeTemplate paaSNodeTemplate) {
        try {
            return cfyServiceNameFromNodeTemplate(paaSNodeTemplate);
        } catch (Exception e) {
            throw new PaaSDeploymentException("Failed to generate cloudify recipe.", e);
        }
    }

    /**
     * Find the name of the cloudify service that host the given node template.
     *
     * @param paaSNodeTemplate The node template for which to get the service name.
     * @return The id of the service that contains the node template.
     *
     */

    public String cfyServiceNameFromNodeTemplate(final PaaSNodeTemplate paaSNodeTemplate) {
        PaaSNodeTemplate parent = paaSNodeTemplate;
        while (parent != null) {
            if (ToscaUtils.isFromType(NormativeComputeConstants.COMPUTE_TYPE, parent.getIndexedNodeType())) {
                return serviceIdFromNodeTemplateId(parent.getId());
            }
            parent = parent.getParent();
        }
        throw new PaaSTechnicalException("Cannot get the service name: The node template <" + paaSNodeTemplate.getId()
                + "> is not declared as hosted on a compute.");
    }

    public final Path deleteDirectory(final String deploymentId) throws IOException {
        Path topologyRecipeDirectory = recipeDirectoryPath.resolve(deploymentId);
        if (Files.exists(topologyRecipeDirectory)) {
            FileUtil.delete(topologyRecipeDirectory);
        }
        Path zipFilePath = getZipFilePath(topologyRecipeDirectory);
        if (Files.exists(zipFilePath)) {
            Files.delete(zipFilePath);
        }
        return topologyRecipeDirectory;
    }

    protected final Path cleanupDirectory(final String deploymentId) throws IOException {
        Path topologyRecipeDirectory = deleteDirectory(deploymentId);
        Files.createDirectories(topologyRecipeDirectory);
        return topologyRecipeDirectory;
    }

    protected Path createZip(final Path recipePath) throws IOException {
        // Generate application zip file.
        Path zipfilepath = Files.createFile(getZipFilePath(recipePath));

        log.debug("Creating application zip:  {}", zipfilepath);

        FileUtil.zip(recipePath, zipfilepath);

        log.debug("Application zip created");

        return zipfilepath;
    }

    private Path getZipFilePath(final Path recipePath) {
        return recipeDirectoryPath.resolve(recipePath.getFileName() + "-application.zip");
    }

    protected void generateService(final Map<String, PaaSNodeTemplate> nodeTemplates, final Path recipePath, final String serviceId,
            final PaaSNodeTemplate computeNode, ComputeTemplate template, Network network) throws IOException {
        // find the compute template for this service
        String computeTemplate = paaSResourceMatcher.getTemplate(template);
        String networkName = null;
        if (network != null) {
            networkName = paaSResourceMatcher.getNetwork(network);
        }
        log.info("Compute template ID for node <{}> is: [{}]", computeNode.getId(), computeTemplate);
        // create service directory
        Path servicePath = recipePath.resolve(serviceId);
        Files.createDirectories(servicePath);

        RecipeGeneratorServiceContext context = new RecipeGeneratorServiceContext(nodeTemplates);
        context.setServiceId(serviceId);
        context.setServicePath(servicePath);

        // copy internal static resources for the service
        cloudifyCommandGen.copyInternalResources(servicePath);

        // generate the properties file from the service node templates properties.
        recipePropertiesGenerator.generatePropertiesFile(context, computeNode);

        // copy artifacts for the nodes
        this.artifactCopier.copyAllArtifacts(context, computeNode);

        // check for blockStorage
        generateInitShutdownScripts(context, computeNode);

        // Generate installation workflow scripts
        StartEvent creationPlanStart = PaaSPlanGenerator.buildNodeCreationPlan(computeNode);
        generateScript(creationPlanStart, "install", context);
        // Generate startup workflow scripts
        StartEvent startPlanStart = PaaSPlanGenerator.buildNodeStartPlan(computeNode);
        generateScript(startPlanStart, "start", context);

        StartEvent stopPlanStart = PaaSPlanGenerator.buildNodeStopPlan(computeNode);
        generateScript(stopPlanStart, "stop", context);

        // Generate custom commands
        addCustomCommands(computeNode, context);

        // generate global start detection script
        manageGlobalStartDetection(context);

        // generate global stop detection script
        manageGlobalStopDetection(context);

        // TODO generate specific cloudify supported interfaces (monitoring policies)

        // generate the service descriptor
        generateServiceDescriptor(context, serviceId, computeTemplate, networkName, computeNode.getScalingPolicy());
    }

    private void generateInitShutdownScripts(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate computeNode) throws IOException {
        PaaSNodeTemplate blockStorageNode = computeNode.getAttachedNode();
        String initCommand = "{}";
        String shutdownCommand = "{}";
        if (blockStorageNode != null) {
            List<String> executions = Lists.newArrayList();

            // init
            generateInitStartUpStorageScripts(context, blockStorageNode, executions);
            // generate the final init script
            generateScriptWorkflow(context.getServicePath(), scriptDescriptorPath, INIT_LIFECYCLE, executions, null);

            executions.clear();

            // shutdown
            generateShutdownStorageScripts(context, blockStorageNode, executions);
            // generate the final shutdown script
            generateScriptWorkflow(context.getServicePath(), scriptDescriptorPath, SHUTDOWN_LIFECYCLE, executions, null);

            initCommand = "\"" + INIT_LIFECYCLE + ".groovy\"";
            shutdownCommand = "\"" + SHUTDOWN_LIFECYCLE + ".groovy\"";
        }
        context.getAdditionalProperties().put(INIT_COMMAND, initCommand);
        context.getAdditionalProperties().put(SHUTDOWN_COMMAND, shutdownCommand);
    }

    private void generateShutdownStorageScripts(final RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode, List<String> shutdownExecutions)
            throws IOException {

        // generate shutdown BS

        String unmountDeleteCommand = getStorageUnmountDeleteCommand(context, blockStorageNode);
        Map<String, String> velocityProps = Maps.newHashMap();
        velocityProps.put("stoppedEvent", cloudifyCommandGen.getFireEventCommand(blockStorageNode.getId(), PlanGeneratorConstants.STATE_STOPPED));
        velocityProps.put(SHUTDOWN_COMMAND, unmountDeleteCommand);
        generateScriptWorkflow(context.getServicePath(), shutdownBlockStorageScriptDescriptorPath, STORAGE_SHUTDOWN_FILE_NAME, null, velocityProps);
        shutdownExecutions.add(cloudifyCommandGen.getGroovyCommand(STORAGE_SHUTDOWN_FILE_NAME.concat(".groovy")));
    }

    private String getStorageUnmountDeleteCommand(RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode) throws IOException {
        String unmountDeleteCommand = getOperationCommandFromInterface(context, blockStorageNode, NODE_LIFECYCLE_INTERFACE_NAME,
                PlanGeneratorConstants.DELETE_OPERATION_NAME, false, false, true, "volumeId", "device");

        // if no custom management then generate the default routine
        if (StringUtils.isBlank(unmountDeleteCommand)) {
            generateScriptWorkflow(
                    context.getServicePath(),
                    unmountDeleteBlockStorageSCriptDescriptorPath,
                    DEFAULT_STORAGE_UNMOUNT_FILE_NAME,
                    null,
                    MapUtil.newHashMap(
                            new String[] { "deletable" },
                            new String[] { String.valueOf(ToscaUtils.isFromType(NormativeBlockStorageConstants.DELETABLE_BLOCKSTORAGE_TYPE,
                                    blockStorageNode.getIndexedNodeType())) }));
            unmountDeleteCommand = cloudifyCommandGen
                    .getGroovyCommandWithParamsAsVar(DEFAULT_STORAGE_UNMOUNT_FILE_NAME.concat(".groovy"), "volumeId", "device");
        }
        return unmountDeleteCommand;
    }

    private void generateInitStartUpStorageScripts(final RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode, List<String> initExecutions)
    // FIXME try manage it via plan generator
            throws IOException {

        generateInitVolumeIdsScript(context, blockStorageNode, initExecutions);

        // startup (create, attach, format, mount)
        Map<String, String> velocityProps = Maps.newHashMap();
        // events
        // velocityProps.put("initial", cloudifyCommandGen.getFireEventCommand(blockStorageNode.getId(), PlanGeneratorConstants.STATE_INITIAL));
        velocityProps.put("createdEvent",
                cloudifyCommandGen.getFireBlockStorageEventCommand(blockStorageNode.getId(), PlanGeneratorConstants.STATE_CREATED, VOLUME_ID_VAR));
        velocityProps.put("configuredEvent", cloudifyCommandGen.getFireEventCommand(blockStorageNode.getId(), PlanGeneratorConstants.STATE_CONFIGURED));
        velocityProps.put("startedEvent", cloudifyCommandGen.getFireEventCommand(blockStorageNode.getId(), PlanGeneratorConstants.STATE_STARTED));

        String createAttachCommand = getStorageCreateAttachCommand(context, blockStorageNode);
        velocityProps.put(CREATE_COMMAND, createAttachCommand);

        String formatMountCommant = getStorageFormatMountCommand(context, blockStorageNode);
        velocityProps.put(CONFIGURE_COMMAND, formatMountCommant);

        // generate startup BS
        generateScriptWorkflow(context.getServicePath(), startupBlockStorageScriptDescriptorPath, STORAGE_STARTUP_FILE_NAME, null, velocityProps);
        initExecutions.add(cloudifyCommandGen.getGroovyCommand(STORAGE_STARTUP_FILE_NAME.concat(".groovy")));
    }

    private String getStorageFormatMountCommand(RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode) throws IOException {
        String formatMountCommand = getOperationCommandFromInterface(context, blockStorageNode, NODE_LIFECYCLE_INTERFACE_NAME,
                PlanGeneratorConstants.CONFIGURE_OPERATION_NAME, false, false, true, "device");

        // if no custom management then generate the default routine
        if (StringUtils.isBlank(formatMountCommand)) {
            generateScriptWorkflow(context.getServicePath(), formatMountBlockStorageScriptDescriptorPath, DEFAULT_STORAGE_MOUNT_FILE_NAME, null,
                    MapUtil.newHashMap(new String[] { PATH_KEY, FS_KEY }, new String[] { DEFAULT_BLOCKSTORAGE_PATH, DEFAULT_BLOCKSTORAGE_FS }));
            formatMountCommand = cloudifyCommandGen.getGroovyCommandWithParamsAsVar(DEFAULT_STORAGE_MOUNT_FILE_NAME.concat(".groovy"), "device");
        }
        return formatMountCommand;
    }

    private String getStorageCreateAttachCommand(final RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode) throws IOException {
        String createAttachCommand = getOperationCommandFromInterface(context, blockStorageNode, NODE_LIFECYCLE_INTERFACE_NAME,
                PlanGeneratorConstants.CREATE_OPERATION_NAME, false, false, true, CONTEXT_THIS_INSTANCE_ATTRIBUTES + ".volumeId",
                CONTEXT_THIS_SERVICE_ATTRIBUTES + ".storageTemplateId");

        // if no custom management then generate the default routine
        if (StringUtils.isBlank(createAttachCommand)) {
            generateScriptWorkflow(context.getServicePath(), createAttachBlockStorageScriptDescriptorPath, DEFAULT_STORAGE_CREATE_FILE_NAME, null,
                    MapUtil.newHashMap(new String[] { NormativeBlockStorageConstants.DEVICE }, new String[] { DEFAULT_BLOCKSTORAGE_DEVICE }));
            createAttachCommand = cloudifyCommandGen.getGroovyCommand(DEFAULT_STORAGE_CREATE_FILE_NAME.concat(".groovy"));
        }
        return createAttachCommand;
    }

    private void generateInitVolumeIdsScript(RecipeGeneratorServiceContext context, PaaSNodeTemplate blockStorageNode, List<String> executions)
            throws IOException {
        Map<String, String> velocityProps = Maps.newHashMap();
        Map<String, String> properties = blockStorageNode.getNodeTemplate().getProperties();
        String size = null;
        String volumeIds = null;
        if (properties != null) {
            size = properties.get(NormativeBlockStorageConstants.SIZE);
            volumeIds = properties.get(NormativeBlockStorageConstants.VOLUME_ID);
            verifyNoVolumeIdForDeletableStorage(blockStorageNode, volumeIds);
        }

        // setting the storage template ID to be used when creating new volume for this application
        String storageTemplate = StringUtils.isNotBlank(size) ? storageTemplateMatcher.getTemplate(blockStorageNode) : storageTemplateMatcher
                .getDefaultTemplate();
        velocityProps.put("storageTemplateId", storageTemplate);

        // setting the volumes Ids array for instances
        String volumeIdsAsArrayString = "null";
        if (StringUtils.isNotBlank(volumeIds)) {
            String[] volumesIdsArray = volumeIds.split(",");
            volumeIdsAsArrayString = jsonMapper.writeValueAsString(volumesIdsArray);
        }
        velocityProps.put("instancesVolumeIds", volumeIdsAsArrayString);

        generateScriptWorkflow(context.getServicePath(), initStorageScriptDescriptorPath, INIT_STORAGE_SCRIPT_FILE_NAME, null, velocityProps);
        executions.add(cloudifyCommandGen.getGroovyCommand(INIT_STORAGE_SCRIPT_FILE_NAME.concat(".groovy")));
    }

    private void verifyNoVolumeIdForDeletableStorage(PaaSNodeTemplate blockStorageNode, String volumeIds) {
        if (ToscaUtils.isFromType(NormativeBlockStorageConstants.DELETABLE_BLOCKSTORAGE_TYPE, blockStorageNode.getIndexedNodeType())
                && StringUtils.isNotBlank(volumeIds)) {
            throw new PaaSDeploymentException("Failed to generate scripts for BlockStorage <" + blockStorageNode.getId() + " >. A storage of type <"
                    + NormativeBlockStorageConstants.DELETABLE_BLOCKSTORAGE_TYPE + "> should not be provided with volumeIds.");
        }
    }

    private void manageGlobalStartDetection(final RecipeGeneratorServiceContext context) throws IOException {
        manageDetectionStep(context, START_DETECTION_SCRIPT_FILE_NAME, SERVICE_START_DETECTION_COMMAND, context.getStartDetectionCommands(), AND_OPERATOR,
                detectionScriptDescriptorPath);
    }

    private void manageGlobalStopDetection(final RecipeGeneratorServiceContext context) throws IOException {
        manageDetectionStep(context, STOP_DETECTION_SCRIPT_FILE_NAME, SERVICE_STOP_DETECTION_COMMAND, context.getStopDetectionCommands(), OR_OPERATOR,
                detectionScriptDescriptorPath);
    }

    private void manageDetectionStep(final RecipeGeneratorServiceContext context, final String stepName, final String stepCommandName,
            final Map<String, String> stepCommandsMap, final String commandsLogicalOperator, final Path velocityDescriptorPath) throws IOException {

        if (MapUtils.isNotEmpty(stepCommandsMap)) {

            // get a formated command for all commands found: command1 && command2 && command3 or command1 || command2 || command3
            // this assumes that every command should return a boolean
            String detectioncommand = cloudifyCommandGen.getMultipleGroovyCommand(commandsLogicalOperator, stepCommandsMap.values().toArray(new String[0]));
            generateScriptWorkflow(context.getServicePath(), velocityDescriptorPath, stepName, null,
                    MapUtil.newHashMap(new String[] { SERVICE_DETECTION_COMMAND, "is" + stepName }, new Object[] { detectioncommand, true }));

            String detectionFilePath = stepName + ".groovy";
            String groovyCommandForClosure = cloudifyCommandGen.getClosureGroovyCommand(detectionFilePath);
            String globalDectctionClosure = cloudifyCommandGen.getReturnGroovyCommand(groovyCommandForClosure);
            context.getAdditionalProperties().put(stepCommandName, globalDectctionClosure);
        }

    }

    private void addCustomCommands(final PaaSNodeTemplate nodeTemplate, final RecipeGeneratorServiceContext context) throws IOException {
        Interface customInterface = nodeTemplate.getIndexedNodeType().getInterfaces().get(NODE_CUSTOM_INTERFACE_NAME);
        if (customInterface != null) {
            // add the custom commands for each operations
            Map<String, Operation> operations = customInterface.getOperations();
            for (Entry<String, Operation> entry : operations.entrySet()) {
                String relativePath = getNodeTypeRelativePath(nodeTemplate.getIndexedNodeType());
                // copy the implementation artifact of the custom command
                artifactCopier.copyImplementationArtifact(context, nodeTemplate.getCsarPath(), relativePath, entry.getValue().getImplementationArtifact());
                String key = entry.getKey();
                String artifactRef = relativePath + "/" + entry.getValue().getImplementationArtifact().getArtifactRef();
                String artifactType = entry.getValue().getImplementationArtifact().getArtifactType();
                String command;
                if (GROOVY_ARTIFACT_TYPE.equals(artifactType)) {
                    command = cloudifyCommandGen.getClosureGroovyCommandWithParamsAsVar(artifactRef, "args");
                } else {
                    // TODO handle SHELL_ARTIFACT_TYPE
                    throw new PaaSDeploymentException("Operation <" + nodeTemplate.getId() + "." + NODE_CUSTOM_INTERFACE_NAME + "." + entry.getKey()
                            + "> is defined using an unsupported artifact type <" + artifactType + ">.");
                }

                if (log.isDebugEnabled()) {
                    log.debug("Configure customCommand " + key + " with value " + command);
                }
                context.getCustomCommands().put(key, command);
            }
        }

        // process childs
        for (PaaSNodeTemplate child : nodeTemplate.getChildren()) {
            addCustomCommands(child, context);
        }

        // process attachedNodes
        if (nodeTemplate.getAttachedNode() != null) {
            addCustomCommands(nodeTemplate.getAttachedNode(), context);
        }
    }

    private void generateScript(final StartEvent startEvent, final String lifecycleName, final RecipeGeneratorServiceContext context) throws IOException {
        List<String> executions = Lists.newArrayList();

        WorkflowStep currentStep = startEvent.getNextStep();
        while (currentStep != null && !(currentStep instanceof StopEvent)) {
            processWorkflowStep(context, currentStep, executions);
            currentStep = currentStep.getNextStep();
        }
        if (lifecycleName.equals("stop")) {
            executions.add(CloudifyCommandGenerator.SHUTDOWN_COMMAND);
            executions.add(CloudifyCommandGenerator.DESTROY_COMMAND);
        }
        if (lifecycleName.equals("start")) {
            executions.add(CloudifyCommandGenerator.DESTROY_COMMAND);
        }
        generateScriptWorkflow(context.getServicePath(), scriptDescriptorPath, lifecycleName, executions, null);
    }

    private void processWorkflowStep(final RecipeGeneratorServiceContext context, final WorkflowStep workflowStep, final List<String> executions)
            throws IOException {
        if (workflowStep instanceof OperationCallActivity) {
            processOperationCallActivity(context, (OperationCallActivity) workflowStep, executions);
        } else if (workflowStep instanceof StateUpdateEvent) {
            StateUpdateEvent stateUpdateEvent = (StateUpdateEvent) workflowStep;
            String command = cloudifyCommandGen.getFireEventCommand(stateUpdateEvent.getElementId(), stateUpdateEvent.getState());
            executions.add(command);
        } else if (workflowStep instanceof ParallelJoinStateGateway) {
            // generate wait for operations
            ParallelJoinStateGateway joinStateGateway = (ParallelJoinStateGateway) workflowStep;
            for (Map.Entry<String, String[]> nodeStates : joinStateGateway.getValidStatesPerElementMap().entrySet()) {
                // TODO supports multiple states
                String command = cloudifyCommandGen.getWaitEventCommand(serviceIdFromNodeTemplateOrDie(context.getNodeTemplateById(nodeStates.getKey())),
                        nodeStates.getKey(), nodeStates.getValue()[0]);
                executions.add(command);
            }
        } else if (workflowStep instanceof ParallelGateway) {
            // generate multi-threaded installation management
            ParallelGateway gateway = (ParallelGateway) workflowStep;
            List<String> parallelScripts = Lists.newArrayList();
            for (WorkflowStep parallelStep : gateway.getParallelSteps()) {
                // create a script for managing this parallel workflow
                String scriptName = "parallel-" + UUID.randomUUID().toString();
                StartEvent parallelStartEvent = new StartEvent();
                parallelStartEvent.setNextStep(parallelStep);
                generateScript(parallelStartEvent, scriptName, context);
                parallelScripts.add(scriptName + ".groovy");
            }
            // now add an execution to the parallel thread management
            String command = cloudifyCommandGen.getParallelCommand(parallelScripts, null);
            executions.add(command);
        } else if (workflowStep instanceof StartEvent || workflowStep instanceof StopEvent) {
            log.debug("No action required to manage start or stop event.");
        } else {
            log.warn("Workflow step <" + workflowStep.getClass() + "> is not managed currently by cloudify PaaS Provider for alien 4 cloud.");
        }
    }

    private void processOperationCallActivity(final RecipeGeneratorServiceContext context, final OperationCallActivity operationCall,
            final List<String> executions) throws IOException {
        if (operationCall.getImplementationArtifact() == null) {
            return;
        }

        PaaSNodeTemplate paaSNodeTemplate = context.getNodeTemplateById(operationCall.getNodeTemplateId());
        if (operationCall.getRelationshipId() == null) {
            boolean isStartOperation = PlanGeneratorConstants.NODE_LIFECYCLE_INTERFACE_NAME.equals(operationCall.getInterfaceName())
                    && PlanGeneratorConstants.START_OPERATION_NAME.equals(operationCall.getOperationName());
            if (isStartOperation) {
                // if there is a stop detection script for this node and the operation is start, then we should inject a stop detection here.
                generateNodeDetectionCommand(context, paaSNodeTemplate, CLOUDIFY_EXTENSIONS_STOP_DETECTION_OPERATION_NAME, context.getStopDetectionCommands(),
                        true);
                // same for the start detection
                generateNodeDetectionCommand(context, paaSNodeTemplate, CLOUDIFY_EXTENSIONS_START_DETECTION_OPERATION_NAME,
                        context.getStartDetectionCommands(), true);
                // now generate the operation start itself
                generateNodeOperationCall(context, operationCall, executions, paaSNodeTemplate, isStartOperation);
                // add the startDetection command to the executions
                addLoppedCommandToExecutions(context.getStartDetectionCommands().get(operationCall.getNodeTemplateId()), executions);
                // we also manage the locator if one is define for this node
                manageProcessLocator(context, paaSNodeTemplate);
            } else {
                generateNodeOperationCall(context, operationCall, executions, paaSNodeTemplate, isStartOperation);
            }
        } else {
            generateRelationshipOperationCall(context, operationCall, executions, paaSNodeTemplate);
        }
    }

    private void addLoppedCommandToExecutions(final String command, final List<String> executions) {
        if (executions != null && StringUtils.isNotBlank(command)) {
            // here, we should add a looped ( "while" wrapped) command to the executions of the node template
            executions.add(cloudifyCommandGen.getLoopedGroovyCommand(command, null));
        }
    }

    private void manageProcessLocator(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate paaSNodeTemplate) throws IOException {
        String command = getOperationCommandFromInterface(context, paaSNodeTemplate, NODE_CLOUDIFY_EXTENSIONS_INTERFACE_NAME,
                CLOUDIFY_EXTENSIONS_LOCATOR_OPERATION_NAME, true, true, false);
        if (command != null) {
            context.getProcessLocatorsCommands().put(paaSNodeTemplate.getId(), command);
        }

    }

    private void generateNodeDetectionCommand(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate paaSNodeTemplate, final String operationName,
            final Map<String, String> commandsMap, final boolean closureCommand) throws IOException {
        String command = getOperationCommandFromInterface(context, paaSNodeTemplate, NODE_CLOUDIFY_EXTENSIONS_INTERFACE_NAME, operationName, true,
                closureCommand, false);
        if (command != null) {
            // here we register the command itself.
            commandsMap.put(paaSNodeTemplate.getId(), command);
        }
    }

    private String getOperationCommandFromInterface(final RecipeGeneratorServiceContext context, final PaaSNodeTemplate nodeTemplate,
            final String interfaceName, final String operationName, final boolean includeDefaultParams, final boolean closureCommand,
            final boolean paramsAsVar, final String... additionalParams) throws IOException {
        String command = null;
        Interface extensionsInterface = nodeTemplate.getIndexedNodeType().getInterfaces().get(interfaceName);
        if (extensionsInterface != null) {
            Operation operation = extensionsInterface.getOperations().get(operationName);
            if (operation != null) {
                String[] parameters = null;
                if (includeDefaultParams) {
                    parameters = new String[] { serviceIdFromNodeTemplateId(nodeTemplate.getId()), serviceIdFromNodeTemplateOrDie(nodeTemplate) };
                }
                parameters = ArrayUtils.addAll(parameters, additionalParams);
                String relativePath = getNodeTypeRelativePath(nodeTemplate.getIndexedNodeType());
                command = getCommandFromOperation(nodeTemplate.getId(), interfaceName, operationName, relativePath, operation.getImplementationArtifact(),
                        closureCommand, paramsAsVar, parameters);
                if (StringUtils.isNotBlank(command)) {
                    this.artifactCopier.copyImplementationArtifact(context, nodeTemplate.getCsarPath(), relativePath, operation.getImplementationArtifact());
                }
            }
        }

        return command;
    }

    private void generateNodeOperationCall(final RecipeGeneratorServiceContext context, final OperationCallActivity operationCall,
            final List<String> executions, final PaaSNodeTemplate paaSNodeTemplate, final boolean isAsynchronous) throws IOException {
        String relativePath = getNodeTypeRelativePath(paaSNodeTemplate.getIndexedNodeType());
        this.artifactCopier.copyImplementationArtifact(context, operationCall.getCsarPath(), relativePath, operationCall.getImplementationArtifact());
        Map<String, Path> copiedArtifactPath = context.getNodeArtifactsPaths().get(paaSNodeTemplate.getId());
        String[] parameters = new String[] { serviceIdFromNodeTemplateId(paaSNodeTemplate.getId()), serviceIdFromNodeTemplateOrDie(paaSNodeTemplate) };
        parameters = addCopiedPathsToParams(copiedArtifactPath, parameters);
        generateOperationCallCommand(context, relativePath, operationCall, parameters, executions, isAsynchronous);
    }

    private String[] addCopiedPathsToParams(Map<String, Path> copiedArtifactPath, String[] parameters) {
        if (MapUtils.isNotEmpty(copiedArtifactPath)) {
            List<String> tempList = Lists.newArrayList(parameters);
            for (Entry<String, Path> artifPath : copiedArtifactPath.entrySet()) {
                tempList.add(escapeForLinuxPath(artifPath));
            }
            parameters = tempList.toArray(new String[tempList.size()]);
        }
        return parameters;
    }

    private String escapeForLinuxPath(Entry<String, Path> pathEntry) {
        String pathStr = pathEntry.toString();
        return pathStr.replaceAll("\\\\", "/");
    }

    private void generateRelationshipOperationCall(final RecipeGeneratorServiceContext context, final OperationCallActivity operationCall,
            final List<String> executions, final PaaSNodeTemplate paaSNodeTemplate) throws IOException {
        PaaSRelationshipTemplate paaSRelationshipTemplate = paaSNodeTemplate.getRelationshipTemplate(operationCall.getRelationshipId());
        String relativePath = getNodeTypeRelativePath(paaSRelationshipTemplate.getIndexedRelationshipType());
        this.artifactCopier.copyImplementationArtifact(context, operationCall.getCsarPath(), relativePath, operationCall.getImplementationArtifact());
        String sourceNodeTemplateId = paaSRelationshipTemplate.getSource();
        String targetNodeTemplateId = paaSRelationshipTemplate.getRelationshipTemplate().getTarget();
        String sourceServiceId = serviceIdFromNodeTemplateOrDie(context.getNodeTemplateById(sourceNodeTemplateId));
        String targetServiceId = serviceIdFromNodeTemplateOrDie(context.getNodeTemplateById(targetNodeTemplateId));
        Map<String, Path> copiedArtifactPath = context.getNodeArtifactsPaths().get(sourceNodeTemplateId);
        String[] parameters = new String[] { serviceIdFromNodeTemplateId(sourceNodeTemplateId), sourceServiceId,
                serviceIdFromNodeTemplateId(targetNodeTemplateId), targetServiceId };
        parameters = addCopiedPathsToParams(copiedArtifactPath, parameters);
        generateOperationCallCommand(context, relativePath, operationCall, parameters, executions, false);
    }

    private void generateOperationCallCommand(final RecipeGeneratorServiceContext context, final String relativePath,
            final OperationCallActivity operationCall, final String[] parameters, final List<String> executions, final boolean isAsynchronous)
            throws IOException {
        // now call the operation script
        String command = getCommandFromOperation(operationCall.getNodeTemplateId(), operationCall.getInterfaceName(), operationCall.getOperationName(),
                relativePath, operationCall.getImplementationArtifact(), false, false, parameters);

        if (isAsynchronous) {
            final String serviceId = serviceIdFromNodeTemplateId(operationCall.getNodeTemplateId());
            String scriptName = "async-" + serviceId + "-" + operationCall.getOperationName() + "-" + UUID.randomUUID().toString();
            generateScriptWorkflow(context.getServicePath(), scriptDescriptorPath, scriptName, Lists.newArrayList(command), null);

            String asyncCommand = cloudifyCommandGen.getAsyncCommand(Lists.newArrayList(scriptName + ".groovy"), null);
            // if we are in the start lifecycle and there is either a startDetection or a stopDetection, then generate a conditional snippet.
            // so that we should only start the node if in restart case and the node is down (startDetetions(stopDetection) failure(success)), or if we are not
            // in the restart case
            if (operationCall.getOperationName().equals(PlanGeneratorConstants.START_OPERATION_NAME)) {
                String restartCondition = getRestartCondition(context, operationCall);
                String contextInstanceAttrRestart = CONTEXT_THIS_INSTANCE_ATTRIBUTES + ".restart";
                if (restartCondition != null) {
                    String trigger = contextInstanceAttrRestart + " != true || (" + restartCondition + ")";
                    // TODO: fire already started state instead
                    String alreadyStartedCommand = cloudifyCommandGen.getFireEventCommand(operationCall.getNodeTemplateId(),
                            PlanGeneratorConstants.STATE_STARTED);
                    String elseCommand = alreadyStartedCommand.concat("\n\t").concat("return");
                    asyncCommand = cloudifyCommandGen.getConditionalSnippet(trigger, asyncCommand, elseCommand);
                } else {
                    // here we try to display a warning message for the restart case
                    log.warn("Node <{}> doesn't have neither startDetection, nor stopDetection lyfecycle event.", serviceId);
                    String warningTrigger = contextInstanceAttrRestart + " == true";
                    String warning = "println \"Neither startDetection nor stopDetetion found for <" + serviceId
                            + ">! will restart the node even if already started.\"";
                    String warningSnippet = cloudifyCommandGen.getConditionalSnippet(warningTrigger, warning, null);
                    executions.add(warningSnippet);
                }
            }
            executions.add(asyncCommand);
        } else {
            executions.add(command);
        }
    }

    private String getCommandFromOperation(final String nodeId, final String interfaceName, final String operationName, final String relativePath,
            final ImplementationArtifact artifact, final boolean closureCommand, final boolean paramsAsVar, final String... parameters) {
        if (artifact == null || StringUtils.isBlank(artifact.getArtifactRef())) {
            return null;
        }

        String scriptPath = relativePath + "/" + artifact.getArtifactRef();
        String command;
        if (GROOVY_ARTIFACT_TYPE.equals(artifact.getArtifactType())) {
            if (paramsAsVar) {
                command = closureCommand ? cloudifyCommandGen.getClosureGroovyCommandWithParamsAsVar(scriptPath, parameters) : cloudifyCommandGen
                        .getGroovyCommandWithParamsAsVar(scriptPath, parameters);
            } else {
                command = closureCommand ? cloudifyCommandGen.getClosureGroovyCommand(scriptPath, parameters) : cloudifyCommandGen.getGroovyCommand(scriptPath,
                        parameters);
            }
        } else if (SHELL_ARTIFACT_TYPE.equals(artifact.getArtifactType())) {
            // TODO pass params to the shell scripts
            command = cloudifyCommandGen.getBashCommand(scriptPath);
        } else {
            throw new PaaSDeploymentException("Operation <" + nodeId + "." + interfaceName + "." + operationName
                    + "> is defined using an unsupported artifact type <" + artifact.getArtifactType() + ">.");
        }
        return command;
    }

    private String getRestartCondition(final RecipeGeneratorServiceContext context, final OperationCallActivity operationCall) {
        String restartCondition = null;
        String startDetectionCommand = context.getStartDetectionCommands().get(operationCall.getNodeTemplateId());
        String instanceRestartContextAttr = CONTEXT_THIS_INSTANCE_ATTRIBUTES + ".restart";
        if (startDetectionCommand != null) {
            restartCondition = instanceRestartContextAttr + " == true && !" + startDetectionCommand;
        } else {
            String stopDetectionCommand = context.getStopDetectionCommands().get(operationCall.getNodeTemplateId());
            if (stopDetectionCommand != null) {
                restartCondition = instanceRestartContextAttr + " == true && " + stopDetectionCommand;
            }
        }
        return restartCondition;
    }

    /**
     * Compute the path of the node type of a node template relative to the service root directory.
     *
     * @param indexedToscaElement The element for which to generate and get it's directory relative path.
     * @return The relative path of the node template's type artifacts in the service directory.
     */
    public static String getNodeTypeRelativePath(final IndexedToscaElement indexedToscaElement) {
        return indexedToscaElement.getElementId() + "-" + indexedToscaElement.getArchiveVersion();
    }

    private void generateScriptWorkflow(final Path servicePath, final Path velocityDescriptorPath, final String lifecycle, final List<String> executions,
            final Map<String, ? extends Object> additionalPropeties) throws IOException {
        Path outputPath = servicePath.resolve(lifecycle + ".groovy");

        HashMap<String, Object> properties = Maps.newHashMap();
        properties.put(SCRIPT_LIFECYCLE, lifecycle);
        properties.put(SCRIPTS, executions);
        if (additionalPropeties != null) {
            properties.putAll(additionalPropeties);
        }
        VelocityUtil.writeToOutputFile(velocityDescriptorPath, outputPath, properties);
    }

    private void generateServiceDescriptor(final RecipeGeneratorServiceContext context, final String serviceName, final String computeTemplate,
            final String networkName, final ScalingPolicy scalingPolicy) throws IOException {
        Path outputPath = context.getServicePath().resolve(context.getServiceId() + DSLUtils.SERVICE_DSL_FILE_NAME_SUFFIX);

        // configure and write the service descriptor thanks to velocity.
        HashMap<String, Object> properties = Maps.newHashMap();
        properties.put(SERVICE_NAME, serviceName);
        properties.put(SERVICE_COMPUTE_TEMPLATE_NAME, computeTemplate);
        properties.put(SERVICE_NETWORK_NAME, networkName);
        if (scalingPolicy != null) {
            properties.put(SERVICE_NUM_INSTANCES, scalingPolicy.getInitialInstances());
            properties.put(SERVICE_MIN_ALLOWED_INSTANCES, scalingPolicy.getMinInstances());
            properties.put(SERVICE_MAX_ALLOWED_INSTANCES, scalingPolicy.getMaxInstances());
        } else {
            properties.put(SERVICE_NUM_INSTANCES, DEFAULT_INIT_MIN_INSTANCE);
            properties.put(SERVICE_MIN_ALLOWED_INSTANCES, DEFAULT_INIT_MIN_INSTANCE);
            properties.put(SERVICE_MAX_ALLOWED_INSTANCES, DEFAULT_MAX_INSTANCE);
        }
        properties.put(SERVICE_CUSTOM_COMMANDS, context.getCustomCommands());
        for (Entry<String, String> entry : context.getAdditionalProperties().entrySet()) {
            properties.put(entry.getKey(), entry.getValue());
        }
        properties.put(LOCATORS, context.getProcessLocatorsCommands());

        VelocityUtil.writeToOutputFile(serviceDescriptorPath, outputPath, properties);
    }

    protected void generateApplicationDescriptor(final Path recipePath, final String topologyId, final String deploymentName, final List<String> serviceIds)
            throws IOException {
        // configure and write the application descriptor thanks to velocity.
        Path outputPath = recipePath.resolve(topologyId + DSLUtils.APPLICATION_DSL_FILE_NAME_SUFFIX);

        HashMap<String, Object> properties = Maps.newHashMap();
        properties.put(APPLICATION_NAME, deploymentName);
        properties.put(APPLICATION_SERVICES, serviceIds);

        VelocityUtil.writeToOutputFile(applicationDescriptorPath, outputPath, properties);
    }

    @Required
    @Value("${directories.alien}/cloudify")
    public void setRecipeDirectoryPath(final String path) {
        log.debug("Setting temporary path to {}", path);
        recipeDirectoryPath = Paths.get(path).toAbsolutePath();
    }
}