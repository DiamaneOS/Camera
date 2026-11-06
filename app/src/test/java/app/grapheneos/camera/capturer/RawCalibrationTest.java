package app.grapheneos.camera.capturer;
import org.junit.Test;
import static org.junit.Assert.*;
public class RawCalibrationTest {
    @Test public void asymmetricCfaSitesKeepTheirBlackAndNoiseValues() {
        float[] black={61,62,63,64};double[] noise={.01,.001,.02,.002,.03,.003,.04,.004};
        float[] result=RawCalibration.encode(black,1023,noise);
        assertArrayEquals(new float[]{61,62,63,64,1023,.01f,.001f,.02f,.002f,.03f,.003f,.04f,.004f},result,0);
    }
    @Test(expected=IllegalArgumentException.class) public void nonfiniteNoiseFailsClosed() {
        RawCalibration.encode(new float[]{64,64,64,64},1023,new double[]{0,0,0,0,Double.NaN,0,0,0});
    }
    @Test(expected=IllegalArgumentException.class) public void blackCannotReachWhite() {
        RawCalibration.encode(new float[]{64,64,1023,64},1023,new double[8]);
    }
}
