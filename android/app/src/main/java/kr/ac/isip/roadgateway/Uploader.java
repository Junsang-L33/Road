package kr.ac.isip.roadgateway;

import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

final class Uploader {
    static final class UploadException extends java.io.IOException {
        private final int status;
        UploadException(int status) { super("Server HTTP " + status); this.status = status; }
        boolean isEventError() { return status == 409 || status == 413 || status == 422; }
    }

    static void upload(String baseUrl, String token, JSONObject event, java.io.File image) throws Exception {
        URI uri = URI.create(baseUrl.replaceAll("/+$", "") + "/api/events");
        if (!("https".equals(uri.getScheme()) || (BuildConfig.DEBUG && "http".equals(uri.getScheme()))))
            throw new IllegalArgumentException("Use HTTPS (debug also permits local HTTP)");
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            String boundary = "RoadGateway" + UUID.randomUUID().toString().replace("-", "");
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setInstanceFollowRedirects(false);
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
            if (!token.trim().isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + token);
            JSONObject metadata = new JSONObject(event.toString());
            metadata.remove("uploaded");
            metadata.remove("receivedAt");
            try (OutputStream output = connection.getOutputStream()) {
                write(output, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"metadata\"\r\nContent-Type: application/json\r\n\r\n");
                write(output, metadata.toString());
                write(output, "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"" + event.getString("eventId") + ".jpg\"\r\nContent-Type: image/jpeg\r\n\r\n");
                Files.copy(image.toPath(), output);
                write(output, "\r\n--" + boundary + "--\r\n");
            }
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) throw new UploadException(status);
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream reply = new ByteArrayOutputStream();
                byte[] buffer = new byte[1024];
                int length;
                while ((length = input.read(buffer)) != -1) {
                    if (reply.size() + length > 16384) throw new java.io.IOException("Server response too large");
                    reply.write(buffer, 0, length);
                }
                JSONObject response = new JSONObject(reply.toString(StandardCharsets.UTF_8.name()));
                if (!event.getString("eventId").equals(response.optString("eventId")))
                    throw new java.io.IOException("Server event ID mismatch");
                String result = response.optString("status");
                if (!(result.equals("saved") || result.equals("already_saved")))
                    throw new java.io.IOException("Server did not confirm storage");
            }
        } finally { connection.disconnect(); }
    }

    private static void write(OutputStream output, String value) throws Exception {
        output.write(value.getBytes(StandardCharsets.UTF_8));
    }
}
