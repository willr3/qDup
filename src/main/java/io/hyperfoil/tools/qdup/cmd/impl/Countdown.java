package io.hyperfoil.tools.qdup.cmd.impl;

import io.hyperfoil.tools.qdup.cmd.Cmd;
import io.hyperfoil.tools.qdup.cmd.Context;


public class Countdown extends Cmd {
    private static final String NO_NAME = null;
    private String name;
    private int initial;
    public Countdown(int count){
        super();
        this.name = NO_NAME;
        this.initial = count;
    }
    public Countdown(String name, int count){
        this.name = name;
        this.initial = count;
    }
    public boolean hasName(){
        return NO_NAME != name && !name.isBlank();
    }
    public String getName(){
        if(name == NO_NAME){
            return "countdown-"+getUid();
        }
        return name;
    }
    public int getInitial(){return initial;}

    @Override
    public void run(String input, Context context) {
        int newCount = context.getCoordinator().decrease(getName(),this.initial);
        if(newCount <= 0){
            context.next(input);
        }else{
            context.skip(input);
        }
    }
    @Override
    public Cmd copy() { return new Countdown(this.name,this.initial); }
    @Override
    public String toString(){return "countdown: "+getName()+" "+getInitial();}
}
