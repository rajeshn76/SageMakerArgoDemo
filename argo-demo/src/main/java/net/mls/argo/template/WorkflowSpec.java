package net.mls.argo.template;

import net.mls.argo.template.structure.Metadata;
import net.mls.argo.template.structure.Spec;

public final class WorkflowSpec {
    public String apiVersion = "argoproj.io/v1alpha1";
    public String kind = "Workflow";
    public Metadata metadata;


    public WorkflowSpec(String generateName) {
        metadata = new Metadata(generateName);
    }

    public Spec spec;

    public void setSpec(Spec spec) {
        this.spec = spec;
    }
}
