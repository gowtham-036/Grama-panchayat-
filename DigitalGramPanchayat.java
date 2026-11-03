import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/**
 * DIGITAL GRAM PANCHAYAT SERVICES
 *
 *  - index.html  : the user interface (browser)
 *  - this file   : the application logic (Java web server) + Firebase Firestore storage
 *
 * How to run (Java 11 or higher, no extra libraries):
 *     javac DigitalGramPanchayat.java
 *     java DigitalGramPanchayat
 *  then open  http://localhost:8080  in the browser.
 *  Keep index.html in the same folder from which you run the program.
 *
 * Firebase: Firestore Database must be created in the Firebase Console and its rules
 * must allow read/write while testing. Collections used: citizens, applications, grievances.
 */
public class DigitalGramPanchayat {

    // =====================================================
    // Firebase (Firestore REST API)
    // =====================================================
    static class Firestore {
        static final String PROJECT_ID = "sachavalam-service";
        static final String API_KEY = "AIzaSyByF63AL-7JfrVbwO0DDAYnx7aj7bUnqL8";
        static final String BASE = "https://firestore.googleapis.com/v1/projects/"
                + PROJECT_ID + "/databases/(default)/documents";
        static final HttpClient http = HttpClient.newHttpClient();

        // Create or replace one document
        static void save(String collection, String docId, Map<String, String> data) {
            try {
                StringBuilder sb = new StringBuilder("{\"fields\":{");
                boolean first = true;
                for (Map.Entry<String, String> e : data.entrySet()) {
                    if (!first) sb.append(",");
                    first = false;
                    sb.append("\"").append(esc(e.getKey())).append("\":{\"stringValue\":\"")
                      .append(esc(e.getValue())).append("\"}");
                }
                sb.append("}}");

                HttpRequest req = HttpRequest.newBuilder(
                        URI.create(BASE + "/" + collection + "/" + docId + "?key=" + API_KEY))
                        .header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(sb.toString()))
                        .build();

                HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() != 200) {
                    System.out.println("[Firebase] Save failed (" + res.statusCode()
                            + "). Check that Firestore is created and its rules allow writes.");
                }
            } catch (Exception e) {
                System.out.println("[Firebase] Could not save: " + e.getMessage());
            }
        }

        // Load every document of a collection
        @SuppressWarnings("unchecked")
        static List<Map<String, String>> loadAll(String collection) {
            List<Map<String, String>> out = new ArrayList<>();
            String token = null;
            try {
                do {
                    String url = BASE + "/" + collection + "?pageSize=300&key=" + API_KEY
                            + (token == null ? "" : "&pageToken=" + token);
                    HttpResponse<String> res = http.send(
                            HttpRequest.newBuilder(URI.create(url)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());

                    if (res.statusCode() != 200) {
                        System.out.println("[Firebase] Load failed for '" + collection
                                + "' (" + res.statusCode() + ")");
                        break;
                    }

                    Map<String, Object> root = (Map<String, Object>) new Json(res.body()).parse();
                    List<Object> docs = (List<Object>) root.get("documents");
                    if (docs != null) {
                        for (Object d : docs) {
                            Map<String, Object> fields =
                                    (Map<String, Object>) ((Map<String, Object>) d).get("fields");
                            Map<String, String> row = new LinkedHashMap<>();
                            if (fields != null) {
                                for (Map.Entry<String, Object> e : fields.entrySet()) {
                                    Object v = ((Map<String, Object>) e.getValue()).get("stringValue");
                                    row.put(e.getKey(), v == null ? "" : v.toString());
                                }
                            }
                            out.add(row);
                        }
                    }
                    token = (String) root.get("nextPageToken");
                } while (token != null);
            } catch (Exception e) {
                System.out.println("[Firebase] Could not load '" + collection + "': " + e.getMessage());
            }
            return out;
        }

        static String esc(String s) {
            return s.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }
    }

    // =====================================================
    // Minimal JSON parser / writer
    // =====================================================
    static class Json {
        private final String s;
        private int i = 0;

        Json(String s) {
            this.s = s;
        }

        Object parse() {
            ws();
            char c = s.charAt(i);
            if (c == '{') return obj();
            if (c == '[') return arr();
            if (c == '"') return str();
            int st = i;
            while (i < s.length() && ",}] \n\r\t".indexOf(s.charAt(i)) < 0) i++;
            return s.substring(st, i);
        }

        private Map<String, Object> obj() {
            Map<String, Object> m = new LinkedHashMap<>();
            i++;
            ws();
            if (s.charAt(i) == '}') {
                i++;
                return m;
            }
            while (true) {
                ws();
                String k = str();
                ws();
                i++; // ':'
                m.put(k, parse());
                ws();
                if (s.charAt(i++) == '}') break;
            }
            return m;
        }

        private List<Object> arr() {
            List<Object> l = new ArrayList<>();
            i++;
            ws();
            if (s.charAt(i) == ']') {
                i++;
                return l;
            }
            while (true) {
                l.add(parse());
                ws();
                if (s.charAt(i++) == ']') break;
            }
            return l;
        }

        private String str() {
            i++; // opening quote
            StringBuilder sb = new StringBuilder();
            while (true) {
                char c = s.charAt(i++);
                if (c == '"') break;
                if (c == '\\') {
                    char n = s.charAt(i++);
                    switch (n) {
                        case 'n': sb.append('\n'); break;
                        case 'r': sb.append('\r'); break;
                        case 't': sb.append('\t'); break;
                        case 'u':
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: sb.append(n);
                    }
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        }

        private void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++;
        }
    }

    static String toJson(Object o) {
        if (o == null) return "null";
        if (o instanceof Map) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : ((Map<?, ?>) o).entrySet()) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(Firestore.esc(String.valueOf(e.getKey()))).append("\":")
                  .append(toJson(e.getValue()));
            }
            return sb.append("}").toString();
        }
        if (o instanceof List) {
            StringBuilder sb = new StringBuilder("[");
            boolean first = true;
            for (Object x : (List<?>) o) {
                if (!first) sb.append(",");
                first = false;
                sb.append(toJson(x));
            }
            return sb.append("]").toString();
        }
        if (o instanceof Number || o instanceof Boolean) return o.toString();
        return "\"" + Firestore.esc(o.toString()) + "\"";
    }

    static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    // Passwords are stored as SHA-256 hashes, never as plain text
    static String hash(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] bytes = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : bytes) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // =====================================================
    // Application data (kept in memory, saved to Firebase)
    // =====================================================
    // Every record is a simple map of text fields, so each form can have its own fields.
    static final List<Map<String, String>> citizens = new ArrayList<>();
    static final List<Map<String, String>> applications = new ArrayList<>();
    static final List<Map<String, String>> grievances = new ArrayList<>();

    static int citizenIdCounter = 1001;
    static int applicationIdCounter = 5001;
    static int grievanceIdCounter = 7001;

    // Login sessions (token -> citizen id) and staff sessions
    static final Map<String, String> sessions = new HashMap<>();
    static final Set<String> staffTokens = new HashSet<>();

    static final String STAFF_USER = "admin";
    static final String STAFF_PASS = "admin123";

    static final List<String> SERVICES = Arrays.asList(
            "Birth Certificate", "Death Certificate", "Other Certificate",
            "Tax Payment", "Welfare Scheme", "Grievance");

    static final String[] CITIZEN_FIELDS =
            {"name", "mobile", "email", "dob", "gender", "village", "address", "service"};

    static final Set<String> RESERVED = new HashSet<>(Arrays.asList(
            "id", "kind", "status", "staffRemark", "citizenId", "citizenName",
            "createdAt", "passwordHash"));

    static class ApiError extends RuntimeException {
        final int code;

        ApiError(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    static String g(Map<String, String> m, String key) {
        String v = m.get(key);
        return v == null ? "" : v;
    }

    static Map<String, String> find(List<Map<String, String>> list, String id) {
        for (Map<String, String> r : list) {
            if (g(r, "id").equals(id)) return r;
        }
        return null;
    }

    static int number(String id) {
        String digits = id.replaceAll("\\D", "");
        return digits.isEmpty() ? 0 : Integer.parseInt(digits);
    }

    static Map<String, String> publicCitizen(Map<String, String> c) {
        Map<String, String> copy = new LinkedHashMap<>(c);
        copy.remove("passwordHash");
        return copy;
    }

    static List<Map<String, String>> recordsFor(List<Map<String, String>> list, String citizenId) {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> r : list) {
            if (g(r, "citizenId").equals(citizenId)) out.add(r);
        }
        return out;
    }

    // =====================================================
    // Startup
    // =====================================================
    public static void main(String[] args) throws Exception {

        int port = args.length > 0 ? Integer.parseInt(args[0]) : 8080;

        System.out.println("Connecting to Firebase...");
        loadFromFirebase();

        // Only this computer can open the server (safer for a demo)
        HttpServer server = HttpServer.create(
                new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0);
        server.createContext("/api/", DigitalGramPanchayat::handleApi);
        server.createContext("/", DigitalGramPanchayat::handleStatic);
        server.start();

        System.out.println("\nDigital Gram Panchayat Services is running.");
        System.out.println("Open  http://localhost:" + port + "  in your browser.");
        System.out.println("Staff login: " + STAFF_USER + " / " + STAFF_PASS);
        System.out.println("Press Ctrl+C to stop.");
    }

    static void loadFromFirebase() {

        for (Map<String, String> c : Firestore.loadAll("citizens")) {
            citizens.add(c);
            citizenIdCounter = Math.max(citizenIdCounter, number(g(c, "id")) + 1);
        }
        for (Map<String, String> a : Firestore.loadAll("applications")) {
            applications.add(a);
            applicationIdCounter = Math.max(applicationIdCounter, number(g(a, "id")) + 1);
        }
        for (Map<String, String> gr : Firestore.loadAll("grievances")) {
            grievances.add(gr);
            grievanceIdCounter = Math.max(grievanceIdCounter, number(g(gr, "id")) + 1);
        }

        // First run: create a sample citizen
        if (citizens.isEmpty()) {
            Map<String, String> c = new LinkedHashMap<>();
            c.put("id", String.valueOf(citizenIdCounter++));
            c.put("name", "Ravi Kumar");
            c.put("mobile", "9876543210");
            c.put("email", "ravi@example.com");
            c.put("dob", "");
            c.put("gender", "Male");
            c.put("village", "Bhimavaram");
            c.put("address", "Bhimavaram");
            c.put("service", "");
            c.put("passwordHash", hash("ravi123"));
            c.put("createdAt", Instant.now().toString());
            citizens.add(c);
            Firestore.save("citizens", c.get("id"), c);
            System.out.println("Sample citizen created: ravi@example.com / ravi123");
        }

        System.out.println("Loaded " + citizens.size() + " citizen(s), "
                + applications.size() + " application(s), "
                + grievances.size() + " grievance(s).");
    }

    // =====================================================
    // HTTP handlers
    // =====================================================
    static void handleStatic(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        Path file = Paths.get("index.html");
        byte[] out;
        int code = 200;
        String type = "text/html; charset=utf-8";

        if ((path.equals("/") || path.equals("/index.html")) && Files.exists(file)) {
            out = Files.readAllBytes(file);
        } else {
            code = 404;
            type = "text/plain; charset=utf-8";
            out = "Not found. Keep index.html in the folder where you run the program."
                    .getBytes(StandardCharsets.UTF_8);
        }
        ex.getResponseHeaders().set("Content-Type", type);
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    static void handleApi(HttpExchange ex) throws IOException {
        int code = 200;
        Object result;
        try {
            result = route(ex);
        } catch (ApiError e) {
            code = e.code;
            result = mapOf("error", e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            code = 500;
            result = mapOf("error", "Server error. Please try again.");
        }
        byte[] out = toJson(result).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(code, out.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(out);
        }
    }

    static Object route(HttpExchange ex) throws Exception {
        String path = ex.getRequestURI().getPath();
        boolean post = ex.getRequestMethod().equals("POST");
        Map<String, String> body = post ? readBody(ex) : new LinkedHashMap<>();
        String token = ex.getRequestHeaders().getFirst("X-Token");

        switch (path) {
            case "/api/register":     return register(body);
            case "/api/login":        return login(body);
            case "/api/reset":        return resetPassword(body);
            case "/api/me":           return me(citizenFor(token));
            case "/api/me/update":    return updateMe(citizenFor(token), body);
            case "/api/apply":        return apply(citizenFor(token), body);
            case "/api/track":        return track(ex.getRequestURI().getQuery());
            case "/api/staff/login":  return staffLogin(body);
            case "/api/staff/data":   requireStaff(token); return staffData();
            case "/api/staff/update": requireStaff(token); return staffUpdate(body);
            default: throw new ApiError(404, "Unknown API path.");
        }
    }

    static Map<String, String> readBody(HttpExchange ex) throws IOException {
        byte[] raw = ex.getRequestBody().readNBytes(200_000);
        String text = new String(raw, StandardCharsets.UTF_8).trim();
        Map<String, String> out = new LinkedHashMap<>();
        if (text.isEmpty()) return out;

        Object parsed;
        try {
            parsed = new Json(text).parse();
        } catch (RuntimeException e) {
            throw new ApiError(400, "Invalid request.");
        }
        if (!(parsed instanceof Map)) throw new ApiError(400, "Invalid request.");

        for (Map.Entry<?, ?> e : ((Map<?, ?>) parsed).entrySet()) {
            String v = String.valueOf(e.getValue()).trim();
            if (v.length() > 2000) v = v.substring(0, 2000);
            out.put(String.valueOf(e.getKey()), v);
        }
        return out;
    }

    // =====================================================
    // Citizen features
    // =====================================================
    static Object register(Map<String, String> b) {
        String name = g(b, "name");
        String mobile = g(b, "mobile");
        String email = g(b, "email");
        String password = g(b, "password");

        if (name.isEmpty()) throw new ApiError(400, "Name is required.");
        if (!mobile.matches("[0-9]{10}")) throw new ApiError(400, "Enter a valid 10-digit mobile number.");
        if (!email.contains("@")) throw new ApiError(400, "Enter a valid email address.");
        if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters.");

        for (Map<String, String> c : citizens) {
            if (g(c, "email").equalsIgnoreCase(email) || g(c, "mobile").equals(mobile)) {
                throw new ApiError(409, "An account with this email or mobile number already exists.");
            }
        }

        Map<String, String> c = new LinkedHashMap<>();
        c.put("id", String.valueOf(citizenIdCounter++));
        for (String f : CITIZEN_FIELDS) c.put(f, g(b, f));
        c.put("passwordHash", hash(password));
        c.put("createdAt", Instant.now().toString());

        citizens.add(c);
        Firestore.save("citizens", c.get("id"), c);

        return mapOf("id", c.get("id"),
                "message", "Registration successful! Your Citizen ID is " + c.get("id")
                        + ". You can now login.");
    }

    static Object login(Map<String, String> b) {
        String identity = g(b, "identity");
        String password = g(b, "password");

        if (!identity.isEmpty()) {
            for (Map<String, String> c : citizens) {
                boolean match = g(c, "email").equalsIgnoreCase(identity)
                        || g(c, "mobile").equals(identity);
                if (match && g(c, "passwordHash").equals(hash(password))) {
                    String token = UUID.randomUUID().toString();
                    sessions.put(token, g(c, "id"));
                    return mapOf("token", token, "name", g(c, "name"), "id", g(c, "id"));
                }
            }
        }
        throw new ApiError(401, "Invalid email/mobile number or password.");
    }

    // NOTE: a real system must verify the user with an OTP or email link before this step.
    static Object resetPassword(Map<String, String> b) {
        String identity = g(b, "identity");
        String password = g(b, "password");

        if (password.length() < 6) throw new ApiError(400, "Password must be at least 6 characters.");

        for (Map<String, String> c : citizens) {
            if (!identity.isEmpty() && (g(c, "email").equalsIgnoreCase(identity)
                    || g(c, "mobile").equals(identity))) {
                c.put("passwordHash", hash(password));
                Firestore.save("citizens", g(c, "id"), c);
                return mapOf("message", "Password changed. You can login with the new password.");
            }
        }
        throw new ApiError(404, "No account found with that email or mobile number.");
    }

    static Map<String, String> citizenFor(String token) {
        String id = token == null ? null : sessions.get(token);
        Map<String, String> c = id == null ? null : find(citizens, id);
        if (c == null) throw new ApiError(401, "Please login first.");
        return c;
    }

    static Object me(Map<String, String> c) {
        String id = g(c, "id");
        return mapOf("citizen", publicCitizen(c),
                "applications", recordsFor(applications, id),
                "grievances", recordsFor(grievances, id));
    }

    // Citizen Data Update service
    static Object updateMe(Map<String, String> c, Map<String, String> b) {
        String mobile = g(b, "mobile");
        String address = g(b, "address");

        if (!mobile.matches("[0-9]{10}")) throw new ApiError(400, "Enter a valid 10-digit mobile number.");
        for (Map<String, String> other : citizens) {
            if (other != c && g(other, "mobile").equals(mobile)) {
                throw new ApiError(409, "This mobile number is used by another account.");
            }
        }

        c.put("mobile", mobile);
        c.put("address", address);
        Firestore.save("citizens", g(c, "id"), c);
        return mapOf("message", "Information updated successfully.");
    }

    // Apply for a service / submit a grievance
    static Object apply(Map<String, String> c, Map<String, String> b) {
        String kind = g(b, "kind");
        if (!SERVICES.contains(kind)) throw new ApiError(400, "Unknown service.");

        boolean isGrievance = kind.equals("Grievance");
        if (isGrievance && g(b, "description").isEmpty()) {
            throw new ApiError(400, "Grievance description cannot be empty.");
        }

        Map<String, String> r = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, String> e : b.entrySet()) {
            String key = e.getKey();
            if (RESERVED.contains(key) || !key.matches("[A-Za-z0-9_]{1,40}") || count++ >= 30) continue;
            String value = e.getValue();
            // Store only the last 4 digits of the Aadhaar number
            if (key.equalsIgnoreCase("aadhaar") && value.length() > 4) {
                value = "XXXXXXXX" + value.substring(value.length() - 4);
            }
            r.put(key, value);
        }

        String id = isGrievance ? "GRV-" + (grievanceIdCounter++) : "APP-" + (applicationIdCounter++);
        r.put("id", id);
        r.put("kind", kind);
        r.put("citizenId", g(c, "id"));
        r.put("citizenName", g(c, "name"));
        r.put("status", isGrievance ? "PENDING" : "SUBMITTED");
        r.put("staffRemark", isGrievance ? "Waiting for staff review" : "Waiting for staff verification");
        r.put("createdAt", Instant.now().toString());

        if (isGrievance) {
            grievances.add(r);
            Firestore.save("grievances", id, r);
        } else {
            applications.add(r);
            Firestore.save("applications", id, r);
        }
        return mapOf("id", id, "status", r.get("status"));
    }

    // Public status check by reference ID
    static Object track(String query) {
        String id = "";
        if (query != null) {
            for (String part : query.split("&")) {
                String[] kv = part.split("=", 2);
                if (kv[0].equals("id") && kv.length == 2) {
                    id = URLDecoder.decode(kv[1], StandardCharsets.UTF_8).trim();
                }
            }
        }
        Map<String, String> r = find(applications, id);
        if (r == null) r = find(grievances, id);
        if (r == null) throw new ApiError(404, "No record found with that ID.");

        return mapOf("id", g(r, "id"), "kind", g(r, "kind"), "status", g(r, "status"),
                "staffRemark", g(r, "staffRemark"), "createdAt", g(r, "createdAt"));
    }

    // =====================================================
    // Staff features
    // =====================================================
    static Object staffLogin(Map<String, String> b) {
        if (g(b, "username").equals(STAFF_USER) && g(b, "password").equals(STAFF_PASS)) {
            String token = "staff-" + UUID.randomUUID();
            staffTokens.add(token);
            return mapOf("token", token);
        }
        throw new ApiError(401, "Invalid staff credentials.");
    }

    static void requireStaff(String token) {
        if (token == null || !staffTokens.contains(token)) {
            throw new ApiError(401, "Staff login required.");
        }
    }

    static Object staffData() {
        List<Map<String, String>> cit = new ArrayList<>();
        for (Map<String, String> c : citizens) cit.add(publicCitizen(c));

        List<Map<String, String>> apps = new ArrayList<>(applications);
        List<Map<String, String>> grvs = new ArrayList<>(grievances);
        Collections.reverse(apps);
        Collections.reverse(grvs);

        return mapOf("citizens", cit, "applications", apps, "grievances", grvs);
    }

    static Object staffUpdate(Map<String, String> b) {
        String id = g(b, "id");
        String action = g(b, "action");
        String reason = g(b, "remark");

        String collection = "applications";
        Map<String, String> r = find(applications, id);
        if (r == null) {
            collection = "grievances";
            r = find(grievances, id);
        }
        if (r == null) throw new ApiError(404, "Record not found.");

        String status;
        String remark;
        switch (action) {
            case "verify":
                if (!g(r, "status").equals("SUBMITTED")) {
                    throw new ApiError(400, "Only SUBMITTED applications can be verified.");
                }
                status = "UNDER VERIFICATION";
                remark = "Documents and information are being verified.";
                break;
            case "approve":
                status = "APPROVED";
                remark = "Application verified and approved by Panchayat staff.";
                break;
            case "reject":
                if (reason.isEmpty()) throw new ApiError(400, "A rejection reason is required.");
                status = "REJECTED";
                remark = reason;
                break;
            case "progress":
                status = "IN PROGRESS";
                remark = "Grievance is being looked into by Panchayat staff.";
                break;
            case "resolve":
                status = "RESOLVED";
                remark = "Grievance resolved by Panchayat staff.";
                break;
            default:
                throw new ApiError(400, "Unknown action.");
        }

        r.put("status", status);
        r.put("staffRemark", remark);
        Firestore.save(collection, id, r);
        return mapOf("message", "Updated.", "status", status);
    }
}
