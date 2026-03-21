package be.orbinson.aem.groovy.console.it;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;
import org.apache.commons.codec.binary.Base64;
import org.apache.http.client.entity.UrlEncodedFormEntity;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.message.BasicNameValuePair;
import org.apache.http.util.EntityUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

class GroovyConsoleServiceIT {

    private static final int SLING_PORT = Integer.getInteger("HTTP_PORT", 8080);
    private static final String BASE_URL = "http://localhost:" + SLING_PORT;
    private static final String AUTH_HEADER = "Basic " + Base64.encodeBase64String("admin:admin".getBytes(StandardCharsets.UTF_8));

    private static CloseableHttpClient httpClient;

    @BeforeAll
    static void beforeAll() {
        httpClient = HttpClients.createDefault();
    }

    @AfterAll
    static void afterAll() throws IOException {
        if (httpClient != null) {
            httpClient.close();
        }
    }

    @BeforeEach
    void beforeEach() {
        await().atMost(30, TimeUnit.SECONDS)
                .pollInterval(5, TimeUnit.SECONDS)
                .untilAsserted(() -> assertTrue(servicesAreAvailable()));
    }

    @Test
    void testScriptReturnsOutput() throws Exception {
        JsonObject response = executeScript("print 'hello world'");

        assertNotNull(response, "Could not get response from API");
        assertEquals("hello world", response.get("output").getAsString());
    }

    @Test
    void testScriptReturnsResult() throws Exception {
        JsonObject response = executeScript("return 42");

        assertNotNull(response, "Could not get response from API");
        assertEquals("42", response.get("result").getAsString());
    }

    @Test
    void testScriptCompilationError() throws Exception {
        JsonObject response = executeScript("def {invalid");

        assertNotNull(response, "Could not get response from API");
        String exceptionStackTrace = response.get("exceptionStackTrace").getAsString();
        assertFalse(exceptionStackTrace.isEmpty(), "Expected a compilation error");
    }

    @Test
    void testScriptRuntimeError() throws Exception {
        JsonObject response = executeScript("1 / 0");

        assertNotNull(response, "Could not get response from API");
        String exceptionStackTrace = response.get("exceptionStackTrace").getAsString();
        assertTrue(exceptionStackTrace.contains("ArithmeticException"), "Expected ArithmeticException in stack trace");
    }

    @Test
    void testSessionBindingAvailable() throws Exception {
        JsonObject response = executeScript("return session != null");

        assertNotNull(response, "Could not get response from API");
        assertEquals("true", response.get("result").getAsString());
    }

    @Test
    void testResourceResolverBindingAvailable() throws Exception {
        JsonObject response = executeScript("return resourceResolver != null");

        assertNotNull(response, "Could not get response from API");
        assertEquals("true", response.get("result").getAsString());
    }

    @Test
    void testBundleContextBindingAvailable() throws Exception {
        JsonObject response = executeScript("return bundleContext != null");

        assertNotNull(response, "Could not get response from API");
        assertEquals("true", response.get("result").getAsString());
    }

    @Test
    void testJcrNodeCreation() throws Exception {
        // Create a node
        JsonObject createResponse = executeScript(
                "session.getNode('/').addNode('test-it-node', 'nt:unstructured')\n" +
                "session.save()\n" +
                "return session.nodeExists('/test-it-node')"
        );

        assertNotNull(createResponse, "Could not get response from API");
        assertEquals("", createResponse.get("exceptionStackTrace").getAsString());
        assertEquals("true", createResponse.get("result").getAsString());

        // Clean up
        JsonObject cleanupResponse = executeScript(
                "session.getNode('/test-it-node').remove()\n" +
                "session.save()\n" +
                "return !session.nodeExists('/test-it-node')"
        );

        assertNotNull(cleanupResponse, "Could not get response from API");
        assertEquals("true", cleanupResponse.get("result").getAsString());
    }

    @Test
    void testAuditRecordCreated() throws Exception {
        // Execute a script to generate an audit record
        executeScript("return 'audit-test'");

        // Fetch audit records
        JsonObject auditResponse = doGet("/bin/groovyconsole/audit");

        assertNotNull(auditResponse, "Could not get audit response");
        assertTrue(auditResponse.has("data"), "Expected audit response to contain 'data'");
        assertFalse(auditResponse.getAsJsonArray("data").isEmpty(), "Expected at least one audit record");
    }

    @Test
    void testTableOutput() throws Exception {
        JsonObject response = executeScript(
                "table {\n" +
                "    columns 'Name', 'Value'\n" +
                "    row 'foo', '1'\n" +
                "    row 'bar', '2'\n" +
                "}"
        );

        assertNotNull(response, "Could not get response from API");
        assertEquals("", response.get("exceptionStackTrace").getAsString());

        String result = response.get("result").getAsString();
        JsonObject resultJson = JsonParser.parseString(result).getAsJsonObject();
        JsonObject table = resultJson.getAsJsonObject("table");

        assertEquals(2, table.getAsJsonArray("columns").size());
        assertEquals(2, table.getAsJsonArray("rows").size());
    }

    @Test
    void testGroovyJsonStarImport() throws Exception {
        JsonObject response = executeScript("return new groovy.json.JsonBuilder([a: 1]).toString()");

        assertNotNull(response, "Could not get response from API");
        assertEquals("", response.get("exceptionStackTrace").getAsString());
        assertEquals("{\"a\":1}", response.get("result").getAsString());
    }

    private static boolean servicesAreAvailable() throws IOException {
        HttpGet healthCheck = new HttpGet(BASE_URL + "/system/health.json?tags=systemalive,bundles");
        healthCheck.addHeader("Authorization", AUTH_HEADER);
        try (CloseableHttpResponse response = httpClient.execute(healthCheck)) {
            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            try {
                JsonObject jsonResponse = JsonParser.parseString(body).getAsJsonObject();
                return "OK".equals(jsonResponse.get("overallResult").getAsString());
            } catch (JsonSyntaxException e) {
                return false;
            }
        }
    }

    private static JsonObject doGet(String path) throws IOException {
        HttpGet get = new HttpGet(BASE_URL + path);
        get.addHeader("Authorization", AUTH_HEADER);

        try (CloseableHttpResponse response = httpClient.execute(get)) {
            assertEquals(200, response.getStatusLine().getStatusCode(),
                    "Expected HTTP 200 but got " + response.getStatusLine());

            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            try {
                return JsonParser.parseString(body).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                fail("Could not parse response body as JSON: " + body);
                return null;
            }
        }
    }

    private static JsonObject executeScript(String script) throws IOException {
        HttpPost post = new HttpPost(BASE_URL + "/bin/groovyconsole/post");
        List<BasicNameValuePair> params = new java.util.ArrayList<>();
        params.add(new BasicNameValuePair("script", script));
        post.setEntity(new UrlEncodedFormEntity(params, StandardCharsets.UTF_8));
        post.addHeader("Authorization", AUTH_HEADER);

        try (CloseableHttpResponse response = httpClient.execute(post)) {
            assertEquals(200, response.getStatusLine().getStatusCode(),
                    "Expected HTTP 200 but got " + response.getStatusLine());

            String body = EntityUtils.toString(response.getEntity(), StandardCharsets.UTF_8);
            try {
                return JsonParser.parseString(body).getAsJsonObject();
            } catch (JsonSyntaxException e) {
                fail("Could not parse response body as JSON: " + body);
                return null;
            }
        }
    }
}
