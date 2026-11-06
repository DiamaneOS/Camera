package app.grapheneos.camera.capturer;

import org.tensorflow.lite.DataType;
import org.tensorflow.lite.Interpreter;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Development-only pinned RGB model evaluation in the existing isolated CPU worker. */
public final class RgbDenoiser implements AutoCloseable {
    private static final int SIDE=256, HALO=16, STEP=SIDE-2*HALO;
    private final Interpreter interpreter;
    private final ByteBuffer model;
    private boolean closed;
    private volatile boolean cancelled;

    public RgbDenoiser(byte[] bytes) throws java.security.NoSuchAlgorithmException {
        if(bytes.length!=2_066_724)throw new IllegalArgumentException("Unexpected RGB model size");
        byte[] expected={5,-8,10,-121,100,-31,-112,-78,-5,-74,-75,-52,-74,-112,-89,-117,-96,-112,-46,-66,-57,83,43,-60,-128,29,81,-42,-64,49,-91,105};
        if(!MessageDigest.isEqual(expected,MessageDigest.getInstance("SHA-256").digest(bytes)))
            throw new IllegalArgumentException("Unexpected RGB model hash");
        model=ByteBuffer.allocateDirect(bytes.length).order(ByteOrder.nativeOrder());model.put(bytes).rewind();
        interpreter=new Interpreter(model,new Interpreter.Options().setNumThreads(2).setUseXNNPACK(true).setCancellable(true));
        if(interpreter.getInputTensorCount()!=1 || interpreter.getOutputTensorCount()!=1 ||
            !Arrays.equals(interpreter.getInputTensor(0).shape(),new int[]{1,SIDE,SIDE,3}) ||
            !Arrays.equals(interpreter.getOutputTensor(0).shape(),new int[]{1,SIDE,SIDE,3}) ||
            interpreter.getInputTensor(0).dataType()!=DataType.FLOAT32 || interpreter.getOutputTensor(0).dataType()!=DataType.FLOAT32) {
            interpreter.close();throw new IllegalArgumentException("Unexpected RGB model tensors");
        }
    }
    private static int reflect(int x,int size) {
        int period=2*(size-1);x%=period;if(x<0)x+=period;
        return x<size?x:period-x;
    }
    public int[] denoise(int[] pixels,int width,int height) {
        if(width<8 || height<8 || width>8192 || height>8192 || (long)width*height>16_000_000 || pixels.length!=(long)width*height)
            throw new IllegalArgumentException("Invalid RGB dimensions");
        int[] result=new int[pixels.length];
        ByteBuffer input=ByteBuffer.allocateDirect(SIDE*SIDE*3*4).order(ByteOrder.nativeOrder());
        ByteBuffer output=ByteBuffer.allocateDirect(SIDE*SIDE*3*4).order(ByteOrder.nativeOrder());
        for(int by=0;by<height;by+=STEP)for(int bx=0;bx<width;bx+=STEP) {
            if(cancelled || Thread.currentThread().isInterrupted())throw new CancellationException();
            input.clear();output.clear();
            for(int y=0;y<SIDE;++y)for(int x=0;x<SIDE;++x) {
                int p=pixels[reflect(by+y-HALO,height)*width+reflect(bx+x-HALO,width)];
                input.putFloat(((p>>16)&255)/255f);input.putFloat(((p>>8)&255)/255f);input.putFloat((p&255)/255f);
            }
            input.rewind();interpreter.run(input,output);output.rewind();
            for(int y=0;y<SIDE;++y)for(int x=0;x<SIDE;++x) {
                int pixel=0xff000000;
                for(int c=0;c<3;++c) {
                    float v=output.getFloat();
                    if(!Float.isFinite(v) || v<-.5f || v>1.5f)throw new IllegalStateException("Invalid RGB output");
                    pixel|=Math.min(255,Math.max(0,Math.round(v*255)))<<((2-c)*8);
                }
                if(x>=HALO && x<SIDE-HALO && y>=HALO && y<SIDE-HALO && bx+x-HALO<width && by+y-HALO<height)
                    result[(by+y-HALO)*width+bx+x-HALO]=pixel;
            }
        }
        return result;
    }
    public synchronized void cancel() {cancelled=true;if(!closed)interpreter.setCancelled(true);}
    @Override public synchronized void close() {if(!closed) {closed=true;interpreter.close();}}
}
