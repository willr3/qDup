package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.Context;

import java.io.File;
import java.nio.file.Paths;

public class Upload extends Cmd {
    private String path;
    private String destination;
    String populatedPath;
    String populatedDestination;
    public Upload(String path, String destination){
        this.path = path;
        this.destination = destination;
    }
    public Upload(String path){
        this(path,"");
    }
    public String getPath(){return path;}
    public String getDestination(){return destination;}
    @Override
    public void run(String input, Context context) {
        populatedPath = populateStateVariables(path,this, context);
        populatedDestination =  populateStateVariables(destination ,this, context);

        //if the command needs to use the shell
        if(context.getShell().isActive() && (
                QueueDownload.hasBashEnv(populatedDestination) ||
                populatedDestination.startsWith("~/") ||
                !populatedDestination.startsWith("/") ||
                populatedDestination.endsWith("/")
            )
        ){
            if(isObserving()){
                getObservedCmd().addDeferredCmd(
                        new Upload(populatedPath,populatedDestination)
                );
            }else{
                logger.error("context is busy and not observing when running "+this);
            }
        }else{
            if(QueueDownload.hasBashEnv(populatedDestination)){
                populatedDestination = context.getShell().shSync("export __qdup_ec=$?; echo "+populatedDestination+"; (exit $__qdup_ec)");
            }
            if(populatedDestination.startsWith("~/")){
                String homeDir = context.getShell().shSync("export __qdup_ec=$?; echo ~/; (exit $__qdup_ec)");
                populatedDestination = homeDir+ populatedDestination.substring("~/".length());
            }else if (!populatedDestination.startsWith("/")){
                String pwd = context.getShell().shSync("export __qdup_ec=$?; pwd; (exit $__qdup_ec)");
                String normalized = Paths.get(pwd,populatedDestination).toAbsolutePath().normalize().toString();
                if(populatedDestination.endsWith("/") && !normalized.endsWith("/")){
                    normalized = normalized + "/";
                }
                populatedDestination = normalized;
            }

            //create remote directory
            if(populatedDestination.endsWith("/")) {
                context.getShell().shSync("export __qdup_ec=$?; mkdir -p " + populatedDestination+"; (exit $__qdup_ec)");
                populatedDestination+=(new File(populatedPath)).getName();
            }
            boolean worked = context.getLocal().upload(
                    populatedPath,
                    populatedDestination,
                    context.getShell().getHost()
            );
            if(!worked){
                context.error("failed to upload "+populatedPath+" to "+populatedDestination);
                context.abort(false);
            } else {
                if(context.getHost().isShell()){

                }

            }

        }
        context.next(populatedDestination);

    }

    @Override
    public Cmd copy() {
        return new Upload(this.path,this.destination);
    }
    @Override
    public String toString(){return "upload: "+path+" "+destination;}
    @Override
    public String getLogOutput(String output,Context context){
        String usePath = populatedPath != null ? populatedPath : path;
        String useDestination = populatedDestination != null ? populatedDestination : destination;
        return "upload: "+usePath+" "+useDestination;
    }

}
