package com.social.app.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import com.social.app.repository.UserRepository;
import com.social.app.model.User;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@RestController
@RequestMapping("/api")
public class ContentController {

    private static final String DEFAULT_IMAGE_URL = "https://images.unsplash.com/photo-1508615121316-fe792af62a63?q=80&w=1170&auto=format&fit=crop";

    private static final Map<String, String> IMAGE_URLS = new ConcurrentHashMap<>();
    private static final Map<String, ImageRecord> IMAGE_RECORDS = new ConcurrentHashMap<>();
    private static final Map<String, String> PROFILE_PICTURES = new ConcurrentHashMap<>();
    private static final Map<String, List<String>> USER_IMAGES = new ConcurrentHashMap<>();
    private static final Path UPLOAD_DIR = Paths.get("uploads");
    private static final Path METADATA_FILE = UPLOAD_DIR.resolve("metadata.txt");
    private static final Path PROFILE_METADATA_FILE = UPLOAD_DIR.resolve("profile-mapping.txt");
    private static final Path USER_IMAGES_FILE = UPLOAD_DIR.resolve("user-images.txt");
    private static final String METADATA_DELIMITER = "|";
    private static final List<Map<String, Object>> FEED = new ArrayList<>();

    private static class ImageRecord {
        private final byte[] bytes;
        private final String contentType;
        private final String title;
        private final String location;
        private final String uploadedAt;

        public ImageRecord(byte[] bytes, String contentType, String title, String location, String uploadedAt) {
            this.bytes = bytes;
            this.contentType = contentType;
            this.title = title;
            this.location = location;
            this.uploadedAt = uploadedAt;
        }

        public byte[] getBytes() {
            return bytes;
        }

        public String getContentType() {
            return contentType;
        }

        public String getTitle() {
            return title;
        }

        public String getLocation() {
            return location;
        }

        public String getUploadedAt() {
            return uploadedAt;
        }
    }


    static {
        try {
            initUploadStore();
            initProfileStore();
            initUserImagesStore();
        } catch (IOException e) {
            throw new IllegalStateException("Unable to initialize upload storage", e);
        }
    }

    private static UserRepository userRepository;

    public ContentController(UserRepository userRepository) {
        ContentController.userRepository = userRepository;
    }

    private static Map<String, Object> createFeedItem(String id, String title, String image, String author, int likes) {
        Map<String, Object> item = new HashMap<>();
        item.put("id", id);
        item.put("title", title);
        item.put("image", image);
        item.put("url", "/api/images/" + id);
        item.put("author", author);
        // Resolve display name from user repository when available
        String authorName = author;
        try {
            if (userRepository != null && author != null) {
                User u = userRepository.findByUsername(author).orElse(null);
                if (u != null && u.getUsername() != null && !u.getUsername().isBlank()) {
                    authorName = u.getUsername();
                }
            }
        } catch (Exception ignored) {
        }
        item.put("authorName", authorName);
        item.put("likes", likes);
        return item;
    }

    private static void initUploadStore() throws IOException {
        if (!Files.exists(UPLOAD_DIR)) {
            Files.createDirectories(UPLOAD_DIR);
        }
        if (!Files.exists(METADATA_FILE)) {
            Files.createFile(METADATA_FILE);
        }
        if (!Files.exists(PROFILE_METADATA_FILE)) {
            Files.createFile(PROFILE_METADATA_FILE);
        }

        for (String line : Files.readAllLines(METADATA_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|");
            if (parts.length < 6) {
                continue;
            }

            String id = parts[0];
            String title = new String(Base64.getDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            String location = new String(Base64.getDecoder().decode(parts[2]), StandardCharsets.UTF_8);
            String uploadedAt = parts[3];
            String contentType = parts[5];
            String uploadedUrl = "/api/images/" + id;
            // owner stored optionally as the 7th part
            String owner = parts.length >= 7 ? parts[6] : "uploader";

            ImageRecord record = new ImageRecord(null, contentType, title, location, uploadedAt);
            IMAGE_RECORDS.put(id, record);
            IMAGE_URLS.put(id, uploadedUrl);

            Map<String, Object> item = createFeedItem(id, title, uploadedUrl, owner, 0);
            item.put("location", location);
            item.put("uploadedAt", uploadedAt);
            FEED.add(0, item);
        }
    }

    private static void initProfileStore() throws IOException {
        if (!Files.exists(PROFILE_METADATA_FILE)) {
            Files.createFile(PROFILE_METADATA_FILE);
            return;
        }

        for (String line : Files.readAllLines(PROFILE_METADATA_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            PROFILE_PICTURES.put(parts[0], parts[1]);
        }
    }

    private static void initUserImagesStore() throws IOException {
        if (!Files.exists(USER_IMAGES_FILE)) {
            Files.createFile(USER_IMAGES_FILE);
            return;
        }

        for (String line : Files.readAllLines(USER_IMAGES_FILE, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            String[] parts = line.split("\\|", 2);
            if (parts.length < 2) {
                continue;
            }
            String username = parts[0];
            String[] ids = parts[1].split(",");
            List<String> list = new ArrayList<>();
            for (String id : ids) {
                if (!id.isBlank()) list.add(id);
            }
            USER_IMAGES.put(username, list);
        }
    }

    private static void saveUserImagesMapping() throws IOException {
        if (!Files.exists(UPLOAD_DIR)) {
            Files.createDirectories(UPLOAD_DIR);
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, List<String>> entry : USER_IMAGES.entrySet()) {
            builder.append(entry.getKey()).append(METADATA_DELIMITER).append(String.join(",", entry.getValue())).append(System.lineSeparator());
        }
        Files.writeString(USER_IMAGES_FILE, builder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    private static Path getUploadPath(String imageId) {
        return UPLOAD_DIR.resolve(imageId);
    }

    private static void persistUpload(String imageId, ImageRecord record, String originalFilename) throws IOException {
        if (!Files.exists(UPLOAD_DIR)) {
            Files.createDirectories(UPLOAD_DIR);
        }

        Path imagePath = getUploadPath(imageId);
        Files.write(imagePath, record.getBytes());

        // store owner if known (stored in USER_IMAGES map), append as 7th field
        String owner = "uploader";
        for (Map.Entry<String, List<String>> e : USER_IMAGES.entrySet()) {
            if (e.getValue().contains(imageId)) {
                owner = e.getKey();
                break;
            }
        }

        String metadataLine = String.join(METADATA_DELIMITER,
                imageId,
                Base64.getEncoder().encodeToString(record.getTitle().getBytes(StandardCharsets.UTF_8)),
                Base64.getEncoder().encodeToString(record.getLocation().getBytes(StandardCharsets.UTF_8)),
                record.getUploadedAt(),
                Base64.getEncoder().encodeToString(originalFilename.getBytes(StandardCharsets.UTF_8)),
                record.getContentType(),
                owner);
        Files.writeString(METADATA_FILE, metadataLine + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void saveProfileMapping(String username, String imageId) throws IOException {
        PROFILE_PICTURES.put(username, imageId);
        if (!Files.exists(UPLOAD_DIR)) {
            Files.createDirectories(UPLOAD_DIR);
        }
        StringBuilder builder = new StringBuilder();
        for (Map.Entry<String, String> entry : PROFILE_PICTURES.entrySet()) {
            builder.append(entry.getKey()).append(METADATA_DELIMITER).append(entry.getValue()).append(System.lineSeparator());
        }
        Files.writeString(PROFILE_METADATA_FILE, builder.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
    }

    @GetMapping("/feed")
    public List<Map<String, Object>> feed(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        int from = Math.max(0, page * size);
        int to = Math.min(FEED.size(), from + size);
        return FEED.subList(from, to);
    }

    @GetMapping("/feed/{id}")
    public ResponseEntity<Map<String, Object>> getFeedItem(@PathVariable String id) {
        return FEED.stream()
                .filter(item -> id.equals(item.get("id")))
                .findFirst()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

 @GetMapping("/stories")
public List<Map<String, Object>> stories() {

    List<Map<String, Object>> stories = new ArrayList<>();

    if (PROFILE_PICTURES.isEmpty()) {
        Map<String, Object> defaultStory = new HashMap<>();
        defaultStory.put("id", "your-story");
        defaultStory.put("name", "Your Story");
        defaultStory.put("avatar", "/api/images/profile-1");
        stories.add(defaultStory);
        return stories;
    }

    for (Map.Entry<String, String> entry : PROFILE_PICTURES.entrySet()) {
        String username = entry.getKey();
        String imageId = entry.getValue();

        Map<String, Object> story = new HashMap<>();
        story.put("id", "story-" + username);
        story.put("name", username);
        story.put("avatar", "/api/images/" + imageId);

        stories.add(story);
    }

    return stories;
}

private static String findProfileImageIdIgnoreCase(String username) {
        if (username == null) return null;
        // direct lookup
        String id = PROFILE_PICTURES.get(username);
        if (id != null) return id;
        // case-insensitive search
        for (Map.Entry<String, String> e : PROFILE_PICTURES.entrySet()) {
            if (e.getKey() != null && e.getKey().equalsIgnoreCase(username)) return e.getValue();
        }
        return null;
    }

    @GetMapping("/explore")
    public List<Map<String, Object>> explore() {
        List<Map<String, Object>> cards = new ArrayList<>();

        Map<String, Object> c1 = new HashMap<>();
        c1.put("title", "Waves");
        c1.put("image", "/api/images/explore-1");
        c1.put("accent", "#06b6d4");
        cards.add(c1);

        return cards;
    }

    @GetMapping("/debug/headers")
    public Map<String, String> debugHeaders(HttpServletRequest request) {
        String auth = request.getHeader("Authorization");
        return Map.of("Authorization", auth == null ? "<none>" : auth);
    }

    @GetMapping("/profile/{username}/photos")
    public List<String> profilePhotos(@PathVariable String username) {
        List<String> photos = new ArrayList<>();

        // First, include images from the FEED authored by the username
        for (Map<String, Object> item : FEED) {
            Object author = item.get("author");
            if (author != null && author.toString().equals(username)) {
                Object image = item.get("image");
                if (image != null) photos.add(image.toString());
            }
        }

        // Next, include images explicitly stored for the user (avoid duplicates)
        List<String> ids = USER_IMAGES.get(username);
        if (ids != null) {
            for (String id : ids) {
                String url = "/api/images/" + id;
                if (!photos.contains(url)) photos.add(url);
            }
        }

        // If still empty, return static examples
        if (photos.isEmpty()) {
            photos.add("/api/images/profile-1");
            photos.add("/api/images/post-1");
            photos.add("/api/images/hero-1");
        }

        return photos;
    }

    @GetMapping("/profile/{username}/avatar")
    public Map<String, String> getProfileAvatar(@PathVariable String username) {
        String imageId = PROFILE_PICTURES.get(username);
        String avatarUrl = imageId != null ? "/api/images/" + imageId : "/api/images/profile-1";
        return Map.of("avatar", avatarUrl);
    }

    @PostMapping(path = "/profile/{username}/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadProfileAvatar(@PathVariable String username,
                                                                   @RequestPart("file") MultipartFile file,
                                                                   HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No file uploaded"));
        }

        String imageId = "profile-" + username;
        String baseUrl = request.getRequestURL().toString().replace(request.getRequestURI(), request.getContextPath());
        String uploadedUrl = baseUrl + "/api/images/" + imageId;
        String uploadedAt = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        ImageRecord record = new ImageRecord(
                file.getBytes(),
                file.getContentType() != null ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE,
                file.getOriginalFilename() != null ? file.getOriginalFilename() : "profile",
                "profile",
                uploadedAt
        );

        IMAGE_RECORDS.put(imageId, record);
        IMAGE_URLS.put(imageId, uploadedUrl);
        // determine owner from authenticated principal if available
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = (auth != null && auth.getName() != null && !auth.getName().isBlank()) ? auth.getName() : null;
        String owner = principal != null ? principal : username;
        // ensure USER_IMAGES contains this profile image as owned by the user so metadata stores owner
        USER_IMAGES.computeIfAbsent(owner, k -> new ArrayList<>()).add(imageId);
        saveUserImagesMapping();
        persistUpload(imageId, record, file.getOriginalFilename() != null ? file.getOriginalFilename() : "profile");
        saveProfileMapping(username, imageId);

        Map<String, String> response = new HashMap<>();
        response.put("avatar", uploadedUrl);
        response.put("message", "Profile picture updated.");
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(path = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, String>> uploadImage(@RequestPart("file") MultipartFile file,
                                                           @RequestParam(required = false) String title,
                                                           @RequestParam(required = false) String location,
                                                           @RequestParam(required = false) String username,
                                                           HttpServletRequest request) throws IOException {
        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No file uploaded"));
        }

        String imageId = "upload-" + UUID.randomUUID();
        String baseUrl = request.getRequestURL().toString().replace(request.getRequestURI(), request.getContextPath());
        String uploadedUrl = baseUrl + "/api/images/" + imageId;

        String titleValue = title != null && !title.isBlank() ? title : file.getOriginalFilename();
        String locationValue = location != null && !location.isBlank() ? location : "Unknown location";
        String uploadedAt = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE);

        ImageRecord record = new ImageRecord(
                file.getBytes(),
                file.getContentType() != null ? file.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE,
                titleValue,
                locationValue,
                uploadedAt
        );

        IMAGE_RECORDS.put(imageId, record);
        IMAGE_URLS.put(imageId, uploadedUrl);

        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String principal = (auth != null && auth.getName() != null && !auth.getName().isBlank()) ? auth.getName() : null;
        String owner = principal != null ? principal : (username != null && !username.isBlank() ? username : "you");
        USER_IMAGES.computeIfAbsent(owner, k -> new ArrayList<>()).add(imageId);
        saveUserImagesMapping();
        // persist after updating USER_IMAGES so owner is recorded in metadata
        persistUpload(imageId, record, file.getOriginalFilename() != null ? file.getOriginalFilename() : "upload");

        // Use a relative image path in the feed to avoid duplicate entries
        // where the feed stores absolute URLs but profile builds relative URLs.
        String feedImagePath = "/api/images/" + imageId;
        Map<String, Object> uploadItem = createFeedItem(imageId, titleValue, feedImagePath, owner, 0);
        uploadItem.put("location", locationValue);
        uploadItem.put("uploadedAt", uploadedAt);
        FEED.add(0, uploadItem);

        Map<String, String> response = new HashMap<>();
        response.put("id", imageId);
        response.put("url", uploadedUrl);
        response.put("preview", uploadedUrl);
        response.put("title", titleValue);
        response.put("location", locationValue);
        response.put("uploadedAt", uploadedAt);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping("/images/{id}")
    public ResponseEntity<byte[]> image(@PathVariable String id) throws IOException {
        ImageRecord record = IMAGE_RECORDS.get(id);
        byte[] bytes = record != null ? record.getBytes() : null;
        String contentType = record != null ? record.getContentType() : MediaType.APPLICATION_OCTET_STREAM_VALUE;

        if (bytes == null) {
            Path filePath = getUploadPath(id);
            if (Files.exists(filePath)) {
                bytes = Files.readAllBytes(filePath);
            }
        }

        if (bytes != null) {
            MediaType mediaType;
            try {
                mediaType = MediaType.parseMediaType(contentType);
            } catch (Exception e) {
                mediaType = MediaType.APPLICATION_OCTET_STREAM;
            }
            return ResponseEntity.ok().contentType(mediaType).body(bytes);
        }

        String targetUrl = IMAGE_URLS.getOrDefault(id, DEFAULT_IMAGE_URL);
        if (targetUrl.equals("/api/images/" + id)) {
            targetUrl = DEFAULT_IMAGE_URL;
        }
        HttpHeaders headers = new HttpHeaders();
        headers.setLocation(URI.create(targetUrl));
        return ResponseEntity.status(HttpStatus.FOUND).headers(headers).build();
    }
}
