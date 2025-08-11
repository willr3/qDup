package io.hyperfoil.tools.qdup;

public record Stage (String name,boolean isSequential,boolean ignoreEnv,boolean postDownload){

    public static final Stage SETUP = new Stage("setup",true,false,false);
    public static final Stage RUN = new Stage("run",false,false,true);
    public static final Stage CLEANUP = new Stage("cleanup",true,true,true);

    public boolean ignoreEnv(){return ignoreEnv;}
    public boolean isPostDownload(){return postDownload;}
    public String getName() {
        return name;
    }
    public boolean isSequential(){return isSequential;}
}
