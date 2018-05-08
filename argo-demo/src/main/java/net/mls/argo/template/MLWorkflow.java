package net.mls.argo.template;

import com.google.common.collect.ImmutableMap;
import net.mls.argo.WorkflowConfig;
import net.mls.argo.template.structure.*;
import org.apache.commons.lang.RandomStringUtils;

import java.util.*;

import static net.mls.argo.template.TemplateConstants.*;

public final class MLWorkflow implements WorkflowFactory {

    public WorkflowSpec createBuildingAndServing(WorkflowConfig conf) {
        String rand = RandomStringUtils.randomAlphanumeric(5).toLowerCase();
        String dockerVersion = conf.getDockerVersion() + rand;


        WorkflowSpec p = new WorkflowSpec(conf.getModelType());
        Spec spec = new Spec("building-serving");
        Arguments globalArgs = new Arguments();
        globalArgs.addParameter(new Parameter(FEATURES_PARAM, conf.getFeaturesPath()));
        globalArgs.addParameter(new Parameter(COLUMNS_PARAM, conf.getColumns()));
        globalArgs.addParameter(new Parameter(MODEL_PATH, conf.getModelPath()));
        globalArgs.addParameter(new Parameter(MODEL_TYPE_PARAM, conf.getModelType()));
        globalArgs.addParameter(new Parameter(DOCKER_REPO_PARAM, conf.getDockerRepo()));
        globalArgs.addParameter(new Parameter(DOCKER_VERS_PARAM, dockerVersion));
        spec.setArguments(globalArgs);
        p.setSpec(spec);

        Template ft = new Template("building-serving");

        Step featureEngineering = new Step("feature-engineering", "fe-template");
        Arguments feArgs = new Arguments();
        feArgs.addParameter(new Parameter(JAR_PARAM, conf.getFeatureJar()));
        feArgs.addParameter(new Parameter(INPUT_PARAM, conf.getDataPath()));
        feArgs.addParameter(new Parameter(FE_JAR_PARAM, conf.getFuncJar()));
        feArgs.addParameter(new Parameter(FUNC_PARAM, conf.getFuncName()));
        featureEngineering.setArguments(feArgs);
        ft.addStep(featureEngineering);

        Step modelTraining = new Step("model-training", "mt-template");
        Arguments mtArgs = new Arguments();
        mtArgs.addParameter(new Parameter(JAR_PARAM, conf.getLearningJar()));
        modelTraining.setArguments(mtArgs);
        ft.addStep(modelTraining);


        String modelType = conf.getModelType();
        String buildModelCmd = BP_MODEL_PARAMS;
        String branch;

        if(modelType.equalsIgnoreCase("sentiment")) {
            buildModelCmd += BP_MODEL_SA_PARAMS;
            branch = "sentiment-analysis";
        } else {
            branch = "recommender-engine";
        }


        Step buildAndPush = new Step("build-and-push", "build-push-template");
        Arguments bapArgs = new Arguments();
        bapArgs.addParameter(new Parameter(JAR_PARAM, "{{item.jar}}"));
        bapArgs.addParameter(new Parameter(BRANCH_PARAM, "{{item.git-branch}}"));
        bapArgs.addParameter(new Parameter(CCMD_PARAM, "{{item.cmd}}"));
        buildAndPush.setArguments(bapArgs);
        ft.addStep(buildAndPush);

        // ''

        List<Map<String,String>> bpItems = createBPItems(conf);

        Map<String, String> modelBPMap = ImmutableMap.of("git-branch", "model/"+branch,
                                                        JAR_PARAM, conf.getModelJar(),
                                                        "cmd", buildModelCmd);
        bpItems.add(0, modelBPMap);
        buildAndPush.setItems(bpItems);

        Step modelServing = new Step("model-serving", "serving-template");
        Arguments msArgs = new Arguments();
        msArgs.addParameter(new Parameter(KUBE_PARAM, conf.getKubeWfName()+"-"
                + rand+"-{{item.image}}"));
        msArgs.addParameter(new Parameter(DOCKER_IMAGE_PARAM, "{{item.image}}"));
        modelServing.setArguments(msArgs);
        ft.addStep(modelServing);

        List<Map<String, String>> servingItems = createServingItems(conf);
        Map<String, String> modelServeMap = ImmutableMap.of("image", "model");
        servingItems.add(0, modelServeMap);

        modelServing.setItems(servingItems);

        // Common between FE & MT
        Secret s3AccessSecret = new Secret("s3-credentials", "accessKey");
        Secret s3SecSecret = new Secret("s3-credentials", "secretKey");
        S3 s3 = new S3(conf.getS3Endpoint(), conf.getS3Bucket(), "{{inputs.parameters.jar}}", s3AccessSecret, s3SecSecret);
        Artifact pipelineArtifact = new Artifact(JAR_ART, "/pipeline.jar", s3);

        Map<String, Secret> s3Access = new HashMap<>();
        s3Access.put("secretKeyRef", new Secret("s3-credentials", "accessKey"));
        Map<String, Secret> s3Secret = new HashMap<>();
        s3Secret.put("secretKeyRef", new Secret("s3-credentials", "secretKey"));
        List<Env> s3EnvList = Arrays.asList(new Env(S3_ACCESS, s3Access), new Env(S3_SECRET, s3Secret));

        Resources btResources = new Resources(4096L, 0.3f);


        Template feTemplate = new Template("fe-template");
        Inputs feTemplateInputs = new Inputs();
        feTemplateInputs.addParameter(new Parameter(JAR_PARAM));
        feTemplateInputs.addParameter(new Parameter(INPUT_PARAM));
        feTemplateInputs.addParameter(new Parameter(FE_JAR_PARAM));
        feTemplateInputs.addParameter(new Parameter(FUNC_PARAM));

        feTemplateInputs.addArtifact(pipelineArtifact);
        S3 s3Func = new S3(conf.getS3Endpoint(), conf.getS3Bucket(), "{{inputs.parameters.feature-engineering-jar}}", s3AccessSecret, s3SecSecret);
        Artifact funcArtifact = new Artifact(FUNC_ART, "/feature-engineering.jar", s3Func);
        feTemplateInputs.addArtifact(funcArtifact);
        feTemplate.setInputs(feTemplateInputs);

        Container feContainer = createFEContainer(conf.getFeatureRunner());
        feTemplate.setContainer(feContainer);
        feContainer.setEnv(s3EnvList);
        feTemplate.setResources(btResources);


        Template mtTemplate = new Template("mt-template");
        Inputs mtTemplateInputs = new Inputs();
        mtTemplateInputs.addParameter(new Parameter(JAR_PARAM));
        mtTemplateInputs.addArtifact(pipelineArtifact);
        mtTemplate.setInputs(mtTemplateInputs);

        Container mtContainer = createMTContainer(conf.getTrainingRunner());
        mtContainer.setEnv(s3EnvList);

        mtTemplate.setContainer(mtContainer);
        mtTemplate.setResources(btResources);


        Template bp = new Template("build-push-template");
        Inputs bpInputs = new Inputs();
        bpInputs.addParameter(new Parameter(JAR_PARAM));
        bpInputs.addParameter(new Parameter(BRANCH_PARAM));
        bpInputs.addParameter(new Parameter(CCMD_PARAM));

        Artifact bpJarArtifact = new Artifact(JAR_ART, "/app.jar", s3);
        Git git = new Git("https://github.com/venci6/demos.git", "{{inputs.parameters.branch}}");
        Artifact bpGitArtifact = new Artifact("docker-files", "/docker-files", git);
        bpInputs.addArtifact(bpGitArtifact);
        bpInputs.addArtifact(bpJarArtifact);
        bp.setInputs(bpInputs);


        Container bpContainer = new Container(IMAGE_DOCKER, Arrays.asList("sh", "-c"), Collections.singletonList(BUILD_PUSH_CMD));
        Map<String, Secret> userMap = new HashMap<>();
        userMap.put("secretKeyRef", new Secret("docker-credentials", "username"));
        Map<String, Secret> pwMap = new HashMap<>();
        pwMap.put("secretKeyRef", new Secret("docker-credentials", "password"));
        bpContainer.setEnv(Arrays.asList(new Env(DOCKER_HOST, "127.0.0.1"), new Env(DOCKER_USERNAME, userMap), new Env(DOCKER_PASSWORD, pwMap)));

        bp.setContainer(bpContainer);
        Map<String, Boolean> securityContext = new HashMap<>();
        securityContext.put("privileged", true);
        Sidecar sc = new Sidecar("dind", IMAGE_DIND, securityContext, true);
        List<Sidecar> sidecars = Collections.singletonList(sc);
        bp.setSidecars(sidecars);


        Template st = new Template("serving-template");
        Inputs stInputs = new Inputs();
        stInputs.addParameter(new Parameter(KUBE_PARAM));
        stInputs.addParameter(new Parameter(DOCKER_IMAGE_PARAM));
        st.setInputs(stInputs);

        Resource r = new Resource("create", KUBE_MANIFEST);
        st.setResource(r);

        p.spec.addTemplate(ft);
        p.spec.addTemplate(feTemplate);
        p.spec.addTemplate(mtTemplate);
        p.spec.addTemplate(bp);
        p.spec.addTemplate(st);

        return p;
    }


    public WorkflowSpec createBuildingPipeline(WorkflowConfig conf) {
        String rand = RandomStringUtils.randomAlphanumeric(5).toLowerCase();
        String dockerVersion = conf.getDockerVersion() + rand;


        WorkflowSpec p = new WorkflowSpec(conf.getModelType());
        Spec spec = new Spec("building-pipeline");
        Arguments globalArgs = new Arguments();
        globalArgs.addParameter(new Parameter(FEATURES_PARAM, conf.getFeaturesPath()));
        globalArgs.addParameter(new Parameter(COLUMNS_PARAM, conf.getColumns()));
        globalArgs.addParameter(new Parameter(MODEL_PATH, conf.getModelPath()));
        globalArgs.addParameter(new Parameter(MODEL_TYPE_PARAM, conf.getModelType()));
        spec.setArguments(globalArgs);
        p.setSpec(spec);

        Template ft = new Template("building-pipeline");

        Step featureEngineering = new Step("feature-engineering", "fe-template");
        Arguments feArgs = new Arguments();
        feArgs.addParameter(new Parameter(JAR_PARAM, conf.getFeatureJar()));
        feArgs.addParameter(new Parameter(INPUT_PARAM, conf.getDataPath()));
        feArgs.addParameter(new Parameter(FE_JAR_PARAM, conf.getFuncJar()));
        feArgs.addParameter(new Parameter(FUNC_PARAM, conf.getFuncName()));
        featureEngineering.setArguments(feArgs);
        ft.addStep(featureEngineering);

        Step modelTraining = new Step("model-training", "mt-template");
        Arguments mtArgs = new Arguments();
        mtArgs.addParameter(new Parameter(JAR_PARAM, conf.getLearningJar()));
        modelTraining.setArguments(mtArgs);
        ft.addStep(modelTraining);



        // Common between FE & MT
        Secret s3AccessSecret = new Secret("s3-credentials", "accessKey");
        Secret s3SecSecret = new Secret("s3-credentials", "secretKey");
        S3 s3 = new S3(conf.getS3Endpoint(), conf.getS3Bucket(), "{{inputs.parameters.jar}}", s3AccessSecret, s3SecSecret);
        Artifact pipelineArtifact = new Artifact(JAR_ART, "/pipeline.jar", s3);

        Map<String, Secret> s3Access = new HashMap<>();
        s3Access.put("secretKeyRef", new Secret("s3-credentials", "accessKey"));
        Map<String, Secret> s3Secret = new HashMap<>();
        s3Secret.put("secretKeyRef", new Secret("s3-credentials", "secretKey"));
        List<Env> s3EnvList = Arrays.asList(new Env(S3_ACCESS, s3Access), new Env(S3_SECRET, s3Secret));

        Resources btResources = new Resources(4096L, 0.3f);


        Template feTemplate = new Template("fe-template");
        Inputs feTemplateInputs = new Inputs();
        feTemplateInputs.addParameter(new Parameter(JAR_PARAM));
        feTemplateInputs.addParameter(new Parameter(INPUT_PARAM));
        feTemplateInputs.addParameter(new Parameter(FE_JAR_PARAM));
        feTemplateInputs.addParameter(new Parameter(FUNC_PARAM));

        feTemplateInputs.addArtifact(pipelineArtifact);
        S3 s3Func = new S3(conf.getS3Endpoint(), conf.getS3Bucket(), "{{inputs.parameters.feature-engineering-jar}}", s3AccessSecret, s3SecSecret);
        Artifact funcArtifact = new Artifact(FUNC_ART, "/feature-engineering.jar", s3Func);
        feTemplateInputs.addArtifact(funcArtifact);
        feTemplate.setInputs(feTemplateInputs);

        Container feContainer = createFEContainer(conf.getFeatureRunner());
        feTemplate.setContainer(feContainer);
        feContainer.setEnv(s3EnvList);
        feTemplate.setResources(btResources);


        Template mtTemplate = new Template("mt-template");
        Inputs mtTemplateInputs = new Inputs();
        mtTemplateInputs.addParameter(new Parameter(JAR_PARAM));
        mtTemplateInputs.addArtifact(pipelineArtifact);
        mtTemplate.setInputs(mtTemplateInputs);

        Container mtContainer = createMTContainer(conf.getTrainingRunner());
        mtContainer.setEnv(s3EnvList);

        mtTemplate.setContainer(mtContainer);
        mtTemplate.setResources(btResources);



        p.spec.addTemplate(ft);
        p.spec.addTemplate(feTemplate);
        p.spec.addTemplate(mtTemplate);

        return p;
    }


    public WorkflowSpec createServingPipeline(WorkflowConfig conf) {
        String rand = RandomStringUtils.randomAlphanumeric(5).toLowerCase();
        String dockerVersion = conf.getDockerVersion() + rand;


        WorkflowSpec p = new WorkflowSpec(conf.getModelType());
        Spec spec = new Spec("serving-pipeline");
        Arguments globalArgs = new Arguments();
        globalArgs.addParameter(new Parameter(COLUMNS_PARAM, conf.getColumns()));
//        globalArgs.addParameter(new Parameter(MODEL_PATH, conf.getModelPath()));
        globalArgs.addParameter(new Parameter(MODEL_TYPE_PARAM, conf.getModelType()));
        globalArgs.addParameter(new Parameter(DOCKER_REPO_PARAM, conf.getDockerRepo()));
        globalArgs.addParameter(new Parameter(DOCKER_VERS_PARAM, dockerVersion));
        spec.setArguments(globalArgs);
        p.setSpec(spec);

        Template ft = new Template("serving-pipeline");

        String modelType = conf.getModelType();
        String buildModelCmd = BP_MODEL_PARAMS_2;
        String branch;

        if(modelType.equalsIgnoreCase("sentiment")) {
            buildModelCmd += BP_MODEL_SA_PARAMS;
            branch = "sentiment-analysis";
        } else {
            branch = "recommender-engine";
        }


        Step buildAndPush = new Step("build-and-push", "build-push-template");
        Arguments bapArgs = new Arguments();
        bapArgs.addParameter(new Parameter(JAR_PARAM, "{{item.jar}}"));
        bapArgs.addParameter(new Parameter(BRANCH_PARAM, "{{item.git-branch}}"));
        bapArgs.addParameter(new Parameter(CCMD_PARAM, "{{item.cmd}}"));
        buildAndPush.setArguments(bapArgs);
        ft.addStep(buildAndPush);


        List<Map<String,String>> bpItems = createBPItems(conf);

        String[] models = conf.getModelPath().split(",");
        for(String modelPath: models) {

            Map<String, String> modelBPMap = ImmutableMap.of("git-branch", "model/"+branch,
                    JAR_PARAM, conf.getModelJar(),
                    "cmd", modelPath + " " + buildModelCmd);

            bpItems.add(0, modelBPMap);

        }

        buildAndPush.setItems(bpItems);

        Step modelServing = new Step("model-serving", "serving-template");
        Arguments msArgs = new Arguments();
        msArgs.addParameter(new Parameter(KUBE_PARAM, conf.getKubeWfName()
                + rand+"-{{item.image}}"));
        msArgs.addParameter(new Parameter(DOCKER_IMAGE_PARAM, "{{item.image}}"));
        modelServing.setArguments(msArgs);
        ft.addStep(modelServing);

        List<Map<String, String>> servingItems = createServingItems(conf);
        Map<String, String> modelServeMap = ImmutableMap.of("image", "model");
        servingItems.add(0, modelServeMap);

        modelServing.setItems(servingItems);

        // Common between FE & MT
        Secret s3AccessSecret = new Secret("s3-credentials", "accessKey");
        Secret s3SecSecret = new Secret("s3-credentials", "secretKey");
        S3 s3 = new S3(conf.getS3Endpoint(), conf.getS3Bucket(), "{{inputs.parameters.jar}}", s3AccessSecret, s3SecSecret);
        Artifact pipelineArtifact = new Artifact(JAR_ART, "/pipeline.jar", s3);

        Map<String, Secret> s3Access = new HashMap<>();
        s3Access.put("secretKeyRef", new Secret("s3-credentials", "accessKey"));
        Map<String, Secret> s3Secret = new HashMap<>();
        s3Secret.put("secretKeyRef", new Secret("s3-credentials", "secretKey"));
        List<Env> s3EnvList = Arrays.asList(new Env(S3_ACCESS, s3Access), new Env(S3_SECRET, s3Secret));

        Resources btResources = new Resources(4096L, 0.3f);



        Template bp = new Template("build-push-template");
        Inputs bpInputs = new Inputs();
        bpInputs.addParameter(new Parameter(JAR_PARAM));
        bpInputs.addParameter(new Parameter(BRANCH_PARAM));
        bpInputs.addParameter(new Parameter(CCMD_PARAM));

        Artifact bpJarArtifact = new Artifact(JAR_ART, "/app.jar", s3);
        Git git = new Git("https://github.com/venci6/demos.git", "{{inputs.parameters.branch}}");
        Artifact bpGitArtifact = new Artifact("docker-files", "/docker-files", git);
        bpInputs.addArtifact(bpGitArtifact);
        bpInputs.addArtifact(bpJarArtifact);
        bp.setInputs(bpInputs);


        Container bpContainer = new Container(IMAGE_DOCKER, Arrays.asList("sh", "-c"), Collections.singletonList(BUILD_PUSH_CMD));
        Map<String, Secret> userMap = new HashMap<>();
        userMap.put("secretKeyRef", new Secret("docker-credentials", "username"));
        Map<String, Secret> pwMap = new HashMap<>();
        pwMap.put("secretKeyRef", new Secret("docker-credentials", "password"));
        bpContainer.setEnv(Arrays.asList(new Env(DOCKER_HOST, "127.0.0.1"), new Env(DOCKER_USERNAME, userMap), new Env(DOCKER_PASSWORD, pwMap)));

        bp.setContainer(bpContainer);
        Map<String, Boolean> securityContext = new HashMap<>();
        securityContext.put("privileged", true);
        Sidecar sc = new Sidecar("dind", IMAGE_DIND, securityContext, true);
        List<Sidecar> sidecars = Collections.singletonList(sc);
        bp.setSidecars(sidecars);


        Template st = new Template("serving-template");
        Inputs stInputs = new Inputs();
        stInputs.addParameter(new Parameter(KUBE_PARAM));
        stInputs.addParameter(new Parameter(DOCKER_IMAGE_PARAM));
        st.setInputs(stInputs);

        Resource r = new Resource("create", KUBE_MANIFEST);
        st.setResource(r);

        p.spec.addTemplate(ft);

        p.spec.addTemplate(bp);
        p.spec.addTemplate(st);

        return p;
    }


    private Container createFEContainer(String runner) {
        Container c;
        List<String> bash = Arrays.asList("bash", "-c");

        if(runner.equalsIgnoreCase("FlinkRunner")) {
            c = new Container(IMAGE_FLINK, bash, Collections.singletonList(FE_FLINK_CMD));
            // TODO spark runner for FE
//        } else if(runner.equalsIgnoreCase("SparkRunner")) {
//            c = new Container(IMAGE_JAVA, bash, Collections.singletonList(MT_SPARK_CMD));
        } else {  // DirectRunner
            c = new Container(IMAGE_JAVA, bash, Collections.singletonList(FE_DIRECT_CMD));
        }
        return c;
    }

    private Container createMTContainer(String runner) {
        Container c;
        List<String> bash = Arrays.asList("bash", "-c");

        if(runner.equalsIgnoreCase("FlinkRunner")) {
            c = new Container(IMAGE_FLINK, bash, Collections.singletonList(MT_FLINK_CMD));
        } else if(runner.equalsIgnoreCase("SparkRunner")) {
            c = new Container(IMAGE_JAVA, bash, Collections.singletonList(MT_SPARK_CMD));
        } else {  // DirectRunner
            c = new Container(IMAGE_JAVA, bash, Collections.singletonList(MT_DIRECT_CMD));
        }
        return c;
    }

    private List<Map<String, String>> createBPItems (WorkflowConfig conf) {
        List<Map<String,String>> extraItems = new ArrayList<>();
        if (conf.getEnableStats()) {
            Map<String, String> statsMap = ImmutableMap.of("git-branch", "basic",
                    JAR_PARAM, conf.getStatsJar(),
                    "cmd", BP_STATS_PARAMS);
            extraItems.add(statsMap);
        }

        if (conf.getEnableProcessor()) {
            Map<String, String> processorMap = ImmutableMap.of("git-branch", "basic",
                    JAR_PARAM, conf.getProcessorJar(),
                    "cmd", BP_PROCESS_PARAMS);
            extraItems.add(processorMap);
        }

        return extraItems;
    }


    private List<Map<String, String>> createServingItems (WorkflowConfig conf) {
        List<Map<String,String>> extraItems = new ArrayList<>();
        if (conf.getEnableStats()) {
            Map<String, String> pcServeMap = ImmutableMap.of("image", "stats");
            extraItems.add(pcServeMap);
        }

        if (conf.getEnableProcessor()) {
            Map<String, String> processorServeMap = ImmutableMap.of("image", "processor");
            extraItems.add(processorServeMap);
        }
        return extraItems;
    }
}
