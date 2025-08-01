package io.hyperfoil.tools.qdup;

public enum Stage {
    Invalid("invalid",-1,true,false,false),
    Pending("pending",0,true,false,false),
    PreSetup("pre-setup",1,true,false,false),
    Setup("setup",2,true,false,true),
    Run("run",3,false,true,false),
    PreCleanup("pre-cleanup",4,false,false,false),
    Cleanup("cleanup",5,true,true,false),
    PostCleanup("post-cleanup",6,true,false,false),
    Done("done",7,true,false,false);
    private String name;
    private int order;
    private boolean isSequential;
    private boolean postDownload;
    private boolean setEnv;
    Stage(String name,int order,boolean isSequential,boolean postDownload,boolean setEnv) {
        this.name = name;
        this.order = order;
        this.isSequential = isSequential;
        this.postDownload = postDownload;
        this.setEnv = setEnv;
    }
    public boolean setEnv(){return setEnv;}
    public boolean isPostDownload(){return postDownload;}
    public boolean isBefore(Stage stage){
        return this.order < stage.order;
    }
    public boolean isAfter(Stage stage){
        return this.order > stage.order;
    }

    public String getName() {
        return name;
    }
    public boolean isSequential(){return isSequential;}
}
