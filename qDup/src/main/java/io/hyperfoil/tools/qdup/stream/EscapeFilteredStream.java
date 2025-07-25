package io.hyperfoil.tools.qdup.stream;

import io.hyperfoil.tools.yaup.AsciiArt;
import io.hyperfoil.tools.yaup.Sets;
import org.jboss.logging.Logger;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Set;

/**
 * need to return length of full match or length or partial match
 * just return length of match and then check if last char is m
 */
public class EscapeFilteredStream extends MultiStream {
    private final static Logger logger = Logger.getLogger(MethodHandles.lookup().lookupClass());

    private static final int CR = 13;  //\u000d
    private static final int ESC = 27; //\u001b
    private static final int NULL = 0; //\u0000
    private static final int SHIFT_IN = 15;
    private static final int SHIFT_OUT = 14;

    //https://en.wikipedia.org/wiki/ANSI_escape_code
    private static final Set<Character> CONTROL_SUFFIX = Sets.of(
            'A',//cursor up
            'B',//cursor down
            'C',//cursor forward
            'D',//cursor back
            'E',//cursor next line
            'F',//cursor previous line
            'G',//cursor horizontal absolute
            'H',//cursor position
            'J',//erase display
            'K',//erase in line
            'S',//scroll up
            'T',//scroll down
            'f',//horozontal,vertical position
            'm',//graphical rendering
            'i',//aux port on
            'n',//device status
            's',//save cursor position
            'u', //restore cursor position
            'h',//seen in git output, switches screen?
            'l',//same as 'h', seen in git and supposedly changes screens?
            (char)7, //BEL
            (char)156 //ST
            );

    private byte[] buffered;
    private int writeIndex = 0;

    public EscapeFilteredStream(){this("");}
    public EscapeFilteredStream(String name){
        super(name);
        buffered = new byte[20*1024];
    }
    protected void superWrite(byte b[], int off, int len) throws IOException {
        super.write(b,off,len);
    }

    public void flushBuffer() throws IOException {
        if(writeIndex>0){
            superWrite(buffered,0,writeIndex);
            writeIndex=0;
        }
    }

    public String getBuffered(){
        return new String(buffered,0,writeIndex);
    }

    @Override
    public void flush()throws IOException {
        //flushBuffer();
    }

    @Override
    public void close()throws IOException {
        flushBuffer();
        //why do we not call super.close()
    }

    public void reset(){
        writeIndex = 0;
    }

    @Override
    public void write(byte b[]) throws IOException {
        write(b,0,b.length);
    }
    @Override
    public void write(byte b[], int off, int len) throws IOException {
        try {
            int flushIndex = 0;
            int trailingEscapeIndex = Integer.MAX_VALUE;
            //expand the buffer if needed
            if (writeIndex + len > buffered.length) {
                int needed = writeIndex + len - buffered.length;
                byte[] newBuffer = new byte[buffered.length + needed];
                System.arraycopy(buffered, 0, newBuffer, 0, writeIndex);
                buffered = newBuffer;
            }
            System.arraycopy(b, off, buffered, writeIndex, len);
            writeIndex += len;

            for (int currentIndex = flushIndex; currentIndex < writeIndex; currentIndex++) {
                boolean filtered = false;
                do {
                    filtered = false;
                    int escapeLength = escapeLength(buffered, currentIndex, writeIndex - currentIndex);
                    if (escapeLength > 0 && isEscaped(buffered, currentIndex, escapeLength)) {//is full match, flush to super
                        filtered = true;
                    } else if (escapeLength > 0) {//match reached end of buffer
                        if (trailingEscapeIndex > currentIndex) {
                            trailingEscapeIndex = currentIndex;
                        }
                    }
                    if (filtered) {
                        //broken escape sequences are not supported in terminals
                        //is this true? Don't see that in spec
                        if (flushIndex < currentIndex) {
                            superWrite(buffered, flushIndex, currentIndex - flushIndex);
                        }
                        currentIndex += escapeLength;
                        flushIndex = currentIndex;
                        trailingEscapeIndex = Integer.MAX_VALUE;
                    }
                } while (filtered);
            }
            if (trailingEscapeIndex < Integer.MAX_VALUE) {//flush from flushIndex to trailingPrefixIndex
                if (trailingEscapeIndex - flushIndex > 0) {
                    superWrite(buffered, flushIndex, trailingEscapeIndex - flushIndex);
                }
                flushIndex = trailingEscapeIndex;
            } else {// no matches and no potential matches, flush everything
                superWrite(buffered, flushIndex, writeIndex - flushIndex);
                flushIndex = writeIndex - 1;
                flushIndex = writeIndex;//TODO testing if fixes double write


            }
            if (flushIndex > 0) {
                System.arraycopy(buffered, flushIndex, buffered, 0, writeIndex - flushIndex);
                writeIndex = writeIndex - flushIndex;
            }

        }catch(Exception e){
            logger.error(e.getMessage(),e);
            throw new RuntimeException("b.length="+(b==null?"null":b.length)+" off="+off+" len="+len+" buffered.length="+buffered.length, e);
        }
    }
    //basically just makes sure we have \u001b[...m
    public boolean isEscaped(byte b[],int off,int len){
        boolean rtrn =
                (len ==1 && (b[off] == CR || b[off] == SHIFT_IN || b[off] == SHIFT_OUT)) ||
            (
                b[off]==ESC
                &&
                (
                    (
                        ( len>=3 && b[off+1]=='[' && CONTROL_SUFFIX.contains((char)b[off+len-1]))
                        || (len == 2 && b[off+1]=='=')
                        || (len == 2 && b[off+1]=='>')
                    ) || (
                        len>=15
                        && b[off+ 1]==']'
                        && b[off+ 2]=='7'
                        && b[off+ 3]=='7'
                        && b[off+ 4]=='7'
                        && b[off+ 5]==';'
                        && b[off+ 6]=='p'
                        && b[off+ 7]=='r'
                        && b[off+ 8]=='e'
                        && b[off+ 9]=='e'
                        && b[off+10]=='x'
                        && b[off+11]=='e'
                        && b[off+12]=='c'
                        && b[off+13]==ESC
                        && b[off+14]=='\\'
                    )
                )
            );
        if(!rtrn && len >=5 && b[off]==ESC && b[off+1]==']' && b[off+2]=='0' && b[off+3]==';'){
            int i=0;
            while(off + i < len && b[off + i] != (char)7){
                i++;
            }
            rtrn = off + i < len && b[off + i] == (char) 7;
        }
        return rtrn;
    }
    //return length of match up to len or 0 if match failed
    public int escapeLength(byte b[], int off, int len){
        boolean matching = true;
        int rtrn = 0;
        if( b[off]==ESC ) {//\003
            rtrn = 1;
            if (2 <= len) {
                if (b[off + 1] == '[') {
                    rtrn = 2;//the initial 2 matched characters
                    if (rtrn < len && b[off + rtrn] == '?') {
                        rtrn = 3;// ^[[? indicates the sequence is for private use
                    }
                    while (matching && rtrn < len) {
                        while (rtrn < len && b[off + rtrn] >= '0' && b[off + rtrn] <= '9') {//digit
                            rtrn++;
                        }
                        //not an integer, if ; continue
                        if (rtrn < len) {
                            if (b[off + rtrn] == ';') {
                                rtrn++;
                            } else if (CONTROL_SUFFIX.contains((char) b[off + rtrn])) {
                                rtrn++;//we matched this character too
                                matching = false;//end of match
                            } else {//false alarm, not a valid escape character
                                rtrn = 0;//why do we reset to 0? I don't remember...
                                matching = false;
                            }
                        } else {
                            matching = false;//stop the match at end of len
                        }
                    }
                } else if (b[off + 1] == ']') {
                    rtrn = 2;
                    int i=0;
                    //VTE preexec
                    byte tofind[] = new byte[]{'7','7','7',';','p','r','e','e','x','e','c',ESC,'\\'};
                    while(rtrn+i < len && i < tofind.length && matching ) {
                        matching = tofind[i]==b[off+rtrn+i];
                        if(matching) {
                            i++;
                        }else{
                            i=0;
                            rtrn=0;
                        }
                    }
                    rtrn += i;
                    if(i == 0){//did not match VTE
                        if(len > 2 && b[off + 2] == '0'){
                            rtrn = 2;
                        }
                        if(rtrn == 2 && len > 3 && b[off + 3] == ';'){
                            while(rtrn+i < len && b[off+rtrn+i] != (char)7){
                                i++;
                            }
                            rtrn = rtrn + i;
                            if(off+rtrn < len && b[off+rtrn] == (char)7){
                                rtrn++;
                            }
                        }
                    }

                } else if (b[off + 1] == '=') {
                    rtrn = 2; //^[= is from DEC VT100s application mode
                } else if (b[off + 1] == '>') {
                    rtrn = 2;
                } else {
                    rtrn = 0;
                }
            }
        }else if (b[off] == SHIFT_IN || b[off] == SHIFT_OUT){
            rtrn = 1;
        }/*else if ( b[off] == CR ){ //doing this breaks things
            rtrn = 1;
        }*/else{
            rtrn = 0;
        }
        return rtrn;
    }
}
