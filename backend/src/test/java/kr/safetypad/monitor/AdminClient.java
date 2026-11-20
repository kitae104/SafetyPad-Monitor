package kr.safetypad.monitor;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/** Minimal cookie-aware browser stand-in: follows the real CSRF-cookie + session-cookie flow. */
class AdminClient {
    final TestRestTemplate rest;
    final Map<String, String> cookies = new LinkedHashMap<>();
    ResponseEntity<String> lastLogin;

    AdminClient(TestRestTemplate rest) {
        this.rest = rest;
    }

    ResponseEntity<String> csrf() {
        return exchange("GET", "/api/v1/admin/csrf", null, true);
    }

    AdminClient login(String user, String password) {
        csrf();
        lastLogin = post("/api/v1/admin/login", "{\"username\":\"" + user + "\",\"password\":\"" + password + "\"}");
        return this;
    }

    static AdminClient loggedIn(TestRestTemplate rest) {
        AdminClient c = new AdminClient(rest).login(AbstractPostgresIT.ADMIN_USER, AbstractPostgresIT.ADMIN_PASSWORD);
        if (c.lastLogin.getStatusCode().value() != 200) throw new IllegalStateException("login failed: " + c.lastLogin.getBody());
        return c;
    }

    ResponseEntity<String> get(String path) { return exchange("GET", path, null, true); }
    ResponseEntity<String> post(String path, String json) { return exchange("POST", path, json, true); }
    ResponseEntity<String> postWithoutCsrf(String path, String json) { return exchange("POST", path, json, false); }

    private ResponseEntity<String> exchange(String method, String path, String body, boolean withCsrf) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        if (!cookies.isEmpty()) {
            h.set("Cookie", String.join("; ", cookies.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()));
        }
        if (withCsrf && cookies.containsKey("XSRF-TOKEN") && !method.equals("GET")) {
            h.set("X-XSRF-TOKEN", cookies.get("XSRF-TOKEN"));
        }
        ResponseEntity<String> res = rest.exchange(path, org.springframework.http.HttpMethod.valueOf(method),
                new HttpEntity<>(body, h), String.class);
        List<String> setCookies = res.getHeaders().get("Set-Cookie");
        if (setCookies != null) {
            for (String sc : setCookies) {
                String nv = sc.split(";", 2)[0];
                int eq = nv.indexOf('=');
                String name = nv.substring(0, eq), value = nv.substring(eq + 1);
                if (value.isEmpty() || sc.toLowerCase().contains("max-age=0")) cookies.remove(name); else cookies.put(name, value);
            }
        }
        return res;
    }

    List<String> setCookieHeaders(ResponseEntity<String> r) {
        List<String> l = r.getHeaders().get("Set-Cookie");
        return l == null ? List.of() : l;
    }
}
