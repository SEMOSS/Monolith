package prerna.web.conf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;

import org.junit.jupiter.api.Test;

class RegressionPixelServletUnitTests {

    @Test
    void decodesSdkFormValuesExactlyOnce() {
        assertEquals(Map.of("expression", "SqlQuery();", "insightId", "new", "tz", "Etc/UTC"),
                RegressionPixelServlet.form("expression=SqlQuery%28%29%3B&insightId=new&tz=Etc%2FUTC"));
    }

    @Test
    void rejectsAmbiguousAndUnsupportedFormFields() {
        assertThrows(IllegalArgumentException.class, () -> RegressionPixelServlet.form("expression=a&expression=b"));
        assertThrows(IllegalArgumentException.class, () -> RegressionPixelServlet.form("expression=a&runId=b"));
        assertThrows(IllegalArgumentException.class, () -> RegressionPixelServlet.form("expression=%ZZ"));
        assertThrows(IllegalArgumentException.class, () -> RegressionPixelServlet.form("expression=a&"));
    }
}
