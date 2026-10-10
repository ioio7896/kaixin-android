package com.example.offlineime;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;

public class OfflineHandwritingRecognizerTest {
    @Test public void distinguishesCharactersWithTheSameStrokeCountByShape() {
        assertEquals("十", recognize(line(10, 50, 90, 50), line(50, 10, 50, 90)).get(0));
        assertEquals("二", recognize(line(10, 35, 90, 35), line(10, 68, 90, 68)).get(0));
    }

    @Test public void recognizesCommonThreeStrokeShapes() {
        assertEquals("大", recognize(line(10, 30, 90, 30), line(50, 30, 15, 90),
                line(50, 30, 85, 90)).get(0));
        assertEquals("人", recognize(line(50, 14, 18, 88), line(50, 14, 84, 88)).get(0));
    }

    @Test public void rejectsEmptyInvalidAndExcessiveStrokeInput() {
        assertTrue(OfflineHandwritingRecognizer.recognize(Collections.emptyList()).isEmpty());
        ArrayList<List<OfflineHandwritingRecognizer.Point>> tooMany = new ArrayList<>();
        for (int i = 0; i < 7; i++) tooMany.add(line(i, 0, i + 1, 1));
        assertTrue(OfflineHandwritingRecognizer.recognize(tooMany).isEmpty());
        assertTrue(OfflineHandwritingRecognizer.recognize(Collections.singletonList(
                Collections.singletonList(new OfflineHandwritingRecognizer.Point(Float.NaN, 0)))).isEmpty());
    }

    @Test public void emitsOnlyNearbyAlternativesAndHasBoundedResults() {
        List<String> ten = recognize(line(10, 50, 90, 50), line(50, 10, 50, 90));
        assertFalse(ten.isEmpty());
        assertTrue(ten.size() <= 5);
    }

    @SafeVarargs
    private static List<String> recognize(List<OfflineHandwritingRecognizer.Point>... strokes) {
        return OfflineHandwritingRecognizer.recognize(Arrays.asList(strokes));
    }

    private static List<OfflineHandwritingRecognizer.Point> line(float x1, float y1, float x2, float y2) {
        return Arrays.asList(point(x1, y1), point((x1 * 2 + x2) / 3f, (y1 * 2 + y2) / 3f),
                point((x1 + x2 * 2) / 3f, (y1 + y2 * 2) / 3f), point(x2, y2));
    }

    private static OfflineHandwritingRecognizer.Point point(float x, float y) {
        return new OfflineHandwritingRecognizer.Point(x, y);
    }
}
