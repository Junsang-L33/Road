package kr.ac.isip.roadgateway;

import android.content.Context;
import android.location.Location;
import android.graphics.BitmapFactory;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.security.MessageDigest;
import java.util.Base64;

final class EventStore {
    private final File directory;

    EventStore(Context context) {
        directory = new File(context.getFilesDir(), "events");
        if (!directory.exists() && !directory.mkdirs()) throw new IllegalStateException("Cannot create event storage");
    }

    private void writeAtomic(File target, byte[] bytes) throws Exception {
        File temp = File.createTempFile("pending-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(bytes);
                output.getFD().sync();
            }
            Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } finally { temp.delete(); }
    }

    synchronized JSONObject save(byte[] metadataBytes, byte[] jpeg, Location location) throws Exception {
        JSONObject metadata = new JSONObject(new String(metadataBytes, StandardCharsets.UTF_8));
        String id = metadata.getString("eventId");
        if (!id.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid event ID");
        File marker = new File(directory, id + ".json");
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length, bounds);
        if (bounds.outWidth <= 0 || !"image/jpeg".equals(bounds.outMimeType)) throw new IllegalArgumentException("Invalid JPEG");
        String digest = digest(jpeg);
        File photo = new File(directory, id + ".jpg");
        // A JSON marker alone must never authorize deletion of the Pi's photo.
        if (marker.exists()) {
            JSONObject saved = read(marker);
            String expected = saved.optString("imageSha256", "");
            if (!expected.isEmpty() && !expected.equals(digest)) throw new IllegalArgumentException("Event ID contains a different photo");
            if (!photo.isFile() || !digest(Files.readAllBytes(photo.toPath())).equals(digest)) writeAtomic(photo, jpeg);
            if (expected.isEmpty()) {
                saved.put("imageSha256", digest);
                writeAtomic(marker, saved.toString().getBytes(StandardCharsets.UTF_8));
            }
            return saved;
        }
        metadata.put("receivedAt", Instant.now().toString());
        metadata.put("uploaded", false);
        metadata.put("latitude", location == null ? JSONObject.NULL : location.getLatitude());
        metadata.put("longitude", location == null ? JSONObject.NULL : location.getLongitude());
        metadata.put("locationAccuracy", location == null ? JSONObject.NULL : location.getAccuracy());
        metadata.put("locationAt", location == null ? JSONObject.NULL : Instant.ofEpochMilli(location.getTime()).toString());
        metadata.put("locationSource", "phone");
        metadata.put("imageSha256", digest);
        writeAtomic(photo, jpeg);
        writeAtomic(marker, metadata.toString().getBytes(StandardCharsets.UTF_8));
        return metadata;
    }

    private JSONObject read(File file) throws Exception {
        return new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
    }

    private String digest(byte[] bytes) throws Exception {
        return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    synchronized List<JSONObject> all() throws Exception {
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
        List<JSONObject> events = new ArrayList<>();
        if (files == null) return events;
        Arrays.sort(files, (a,b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (File file : files) {
            try { events.add(read(file)); }
            catch (Exception error) { android.util.Log.e("EventStore", "Unreadable event retained: " + file.getName(), error); }
        }
        return events;
    }

    synchronized void markUploaded(JSONObject event) throws Exception {
        event.put("uploaded", true);
        writeAtomic(new File(directory, event.getString("eventId") + ".json"), event.toString().getBytes(StandardCharsets.UTF_8));
    }

    File image(JSONObject event) throws Exception { return new File(directory, event.getString("eventId") + ".jpg"); }
}
