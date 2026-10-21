package app.grapheneos.camera.capturer;
import org.junit.Test;
import static org.junit.Assert.*;

public class RawCorrectionGridTest {
    @Test public void matchesOnlyQualifiedGeometryAndCfa() {
        String profile="256,192,0,8,4,6,2";
        assertArrayEquals(new int[]{8,4,6,2},RawCorrectionGrid.select(profile,256,192,0));
        assertNull(RawCorrectionGrid.select(profile,128,192,3));
        assertNull(RawCorrectionGrid.select(profile,256,192,3));
    }
    @Test public void absentOrMalformedCalibrationDisablesCorrection() {
        for(String profile:new String[]{"","1,2","256,192,0,8,4,8,2","256,192,0,7,4,6,2",
                "256,192,0,2,4,0,2","256,192,0,64,4,6,2","256,192,0,8,4,-1,2",
                "256,192,0,99999999999999999,4,6,2"})
            assertNull(RawCorrectionGrid.select(profile,256,192,0));
    }
}
