package ucll.be.dammonitorbackend.service;

import com.fasterxml.jackson.databind.JsonNode;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

import javax.imageio.ImageIO;
import javax.imageio.ImageReadParam;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * Searches Digital Earth Africa STAC for a suitable Sentinel-2 scene and
 * renders the scene's visual Cloud-Optimized GeoTIFF as a JPEG preview.
 */
@Service
public class SatelliteService {

    private static final Logger log = LoggerFactory.getLogger(SatelliteService.class);

    private static final double BBOX_MIN_LON = 27.786741;
    private static final double BBOX_MIN_LAT = -25.775772;
    private static final double BBOX_MAX_LON = 27.910681;
    private static final double BBOX_MAX_LAT = -25.721965;
    private static final String SEARCH_DATETIME = "2026-10-01T00:00:00Z/2026-11-15T23:59:59Z";
    private static final double MAX_CLOUD_COVER_PCT = 20.0;
    private static final int MAX_PREVIEW_WIDTH = 1200;
    private static final int MAX_PREVIEW_HEIGHT = 900;
    private static final String VISUAL_ASSET_HOST = "deafrica-sentinel-2.s3.af-south-1.amazonaws.com";

    private final WebClient webClient;
    private final String stacSearchUrl;
    private volatile SatelliteImageResult cachedImage;

    public SatelliteService(
            WebClient webClient,
            @Value("${digital-earth-africa.stac-search-url}") String stacSearchUrl) {
        this.webClient = webClient;
        this.stacSearchUrl = stacSearchUrl;
    }

    public record Collection(String id, String displayName) {
    }

    public record SatelliteImageResult(
            byte[] imageBytes,
            Instant dateTaken,
            Collection collection,
            String assetUrl) {
    }

    public static class SatelliteImageUnavailableException extends RuntimeException {
        public SatelliteImageUnavailableException(String message) {
            super(message);
        }
    }

    private record SceneInfo(
            Instant dateTaken,
            double cloudCover,
            JsonNode feature,
            String visualAssetUrl) {
    }

    public SatelliteImageResult fetchSatelliteImage() {
        return fetchSatelliteImage(false);
    }

    public synchronized SatelliteImageResult fetchSatelliteImage(boolean forceRefresh) {
        if (forceRefresh) {
            log.info("Refreshing cached Digital Earth Africa satellite image");
            cachedImage = null;
        } else if (cachedImage != null) {
            log.debug("Serving cached Digital Earth Africa satellite image");
            return cachedImage;
        }

        log.info("Searching Digital Earth Africa for Sentinel-2 imagery over Hartbeespoort Dam");
        JsonNode searchResponse = searchScenes();
        SceneInfo scene = selectScene(searchResponse);
        log.info("Selected Digital Earth Africa scene at {} with cloud cover {}%",
                scene.dateTaken(), Double.isFinite(scene.cloudCover()) ? scene.cloudCover() : "unavailable");

        JsonNode transform = scene.feature().path("assets").path("visual").path("proj:transform");
        byte[] imageBytes = downloadAndRenderVisualAsset(scene.visualAssetUrl(), transform);
        Collection collection = new Collection("s2_l2a", "Digital Earth Africa Sentinel-2 L2A");
        cachedImage = new SatelliteImageResult(imageBytes, scene.dateTaken(), collection, scene.visualAssetUrl());
        return cachedImage;
    }

    private JsonNode searchScenes() {
        String requestBody = String.format(Locale.ROOT, """
                {
                  "collections": ["s2_l2a"],
                  "bbox": [%f, %f, %f, %f],
                  "datetime": "%s",
                  "limit": 10
                }
                """,
                BBOX_MIN_LON, BBOX_MIN_LAT, BBOX_MAX_LON, BBOX_MAX_LAT, SEARCH_DATETIME);

        JsonNode response = webClient.post()
                .uri(stacSearchUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(requestBody)
                .retrieve()
                .onStatus(
                        status -> status.isError(),
                        result -> result.bodyToMono(String.class).map(body -> {
                            log.error("Digital Earth Africa STAC search failed: {} — {}",
                                    result.statusCode(), body);
                            return new RuntimeException("Digital Earth Africa STAC search failed: " + body);
                        }))
                .bodyToMono(JsonNode.class)
                .block();

        if (response == null) {
            throw new RuntimeException("Digital Earth Africa STAC search returned an empty response");
        }
        return response;
    }

    private SceneInfo selectScene(JsonNode searchResponse) {
        JsonNode features = searchResponse.path("features");
        if (!features.isArray()) {
            throw new RuntimeException("Digital Earth Africa STAC response is missing its features array");
        }

        List<SceneInfo> candidates = new ArrayList<>();
        for (JsonNode feature : features) {
            if (!coversDam(feature.path("bbox")) || !geometryCoversDam(feature.path("geometry"))) {
                continue;
            }

            String datetime = feature.path("properties").path("datetime").asText(null);
            String visualHref = feature.path("assets").path("visual").path("href").asText(null);
            JsonNode transform = feature.path("assets").path("visual").path("proj:transform");
            if (datetime == null || visualHref == null || !transform.isArray() || transform.size() < 6) {
                continue;
            }

            double cloudCover = feature.path("properties").path("eo:cloud_cover").asDouble(Double.NaN);
            if (Double.isFinite(cloudCover) && cloudCover > MAX_CLOUD_COVER_PCT) {
                continue;
            }

            try {
                candidates.add(new SceneInfo(
                        Instant.parse(datetime),
                        cloudCover,
                        feature,
                        toDigitalEarthAfricaHttpsUrl(visualHref)));
            } catch (RuntimeException ex) {
                log.warn("Skipping unsuitable Digital Earth Africa scene: {}", ex.getMessage());
            }
        }

        return candidates.stream()
                .max(Comparator.comparing(SceneInfo::dateTaken)
                        .thenComparingDouble(scene -> Double.isFinite(scene.cloudCover())
                                ? -scene.cloudCover()
                                : Double.NEGATIVE_INFINITY))
                .orElseThrow(() -> new SatelliteImageUnavailableException(
                        "No suitable Digital Earth Africa Sentinel-2 scene is available for Hartbeespoort Dam "
                                + "in the requested date range"));
    }

    private boolean coversDam(JsonNode bbox) {
        return bbox.isArray()
                && bbox.size() >= 4
                && bbox.get(0).asDouble(Double.NaN) <= BBOX_MIN_LON
                && bbox.get(1).asDouble(Double.NaN) <= BBOX_MIN_LAT
                && bbox.get(2).asDouble(Double.NaN) >= BBOX_MAX_LON
                && bbox.get(3).asDouble(Double.NaN) >= BBOX_MAX_LAT;
    }

    private boolean geometryCoversDam(JsonNode geometry) {
        JsonNode coordinates = geometry.path("coordinates");
        List<double[]> checkPoints = List.of(
                new double[] { BBOX_MIN_LON, BBOX_MIN_LAT },
                new double[] { BBOX_MIN_LON, BBOX_MAX_LAT },
                new double[] { BBOX_MAX_LON, BBOX_MIN_LAT },
                new double[] { BBOX_MAX_LON, BBOX_MAX_LAT },
                new double[] { (BBOX_MIN_LON + BBOX_MAX_LON) / 2, (BBOX_MIN_LAT + BBOX_MAX_LAT) / 2 });

        if ("Polygon".equals(geometry.path("type").asText())) {
            return polygonCoversDam(coordinates, checkPoints);
        }
        if ("MultiPolygon".equals(geometry.path("type").asText()) && coordinates.isArray()) {
            for (JsonNode polygon : coordinates) {
                if (polygonCoversDam(polygon, checkPoints)) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean polygonCoversDam(JsonNode rings, List<double[]> checkPoints) {
        if (!rings.isArray() || rings.isEmpty()) {
            return false;
        }
        JsonNode outerRing = rings.get(0);
        for (double[] point : checkPoints) {
            if (!pointInRing(outerRing, point[0], point[1])) {
                return false;
            }
            for (int ringIndex = 1; ringIndex < rings.size(); ringIndex++) {
                if (pointInRing(rings.get(ringIndex), point[0], point[1])) {
                    return false;
                }
            }
        }
        return true;
    }

    private boolean pointInRing(JsonNode ring, double longitude, double latitude) {
        boolean inside = false;
        if (!ring.isArray() || ring.size() < 4) {
            return false;
        }
        for (int i = 0, j = ring.size() - 1; i < ring.size(); j = i++) {
            JsonNode current = ring.get(i);
            JsonNode previous = ring.get(j);
            double currentLon = current.get(0).asDouble();
            double currentLat = current.get(1).asDouble();
            double previousLon = previous.get(0).asDouble();
            double previousLat = previous.get(1).asDouble();
            boolean crossesLatitude = (currentLat > latitude) != (previousLat > latitude);
            if (crossesLatitude && longitude < (previousLon - currentLon) * (latitude - currentLat)
                    / (previousLat - currentLat) + currentLon) {
                inside = !inside;
            }
        }
        return inside;
    }

    private String toDigitalEarthAfricaHttpsUrl(String href) {
        URI uri = URI.create(href);
        if ("s3".equalsIgnoreCase(uri.getScheme())
                && "deafrica-sentinel-2".equals(uri.getHost())
                && uri.getRawPath() != null
                && !uri.getRawPath().isBlank()) {
            return "https://" + VISUAL_ASSET_HOST + uri.getRawPath();
        }
        if ("https".equalsIgnoreCase(uri.getScheme())
                && VISUAL_ASSET_HOST.equalsIgnoreCase(uri.getHost())) {
            return uri.toASCIIString();
        }
        throw new IllegalArgumentException("Unexpected Digital Earth Africa visual asset URL: " + href);
    }

    private byte[] downloadAndRenderVisualAsset(String assetUrl, JsonNode transform) {
        Path cogFile = null;
        try {
            cogFile = Files.createTempFile("deafrica-visual-", ".tif");
            Path downloadPath = cogFile;
            try (OutputStream output = Files.newOutputStream(
                    downloadPath, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                webClient.get()
                        .uri(assetUrl)
                        .accept(MediaType.valueOf("image/tiff"))
                        .retrieve()
                        .onStatus(
                                status -> status.isError(),
                                result -> result.bodyToMono(String.class).map(body -> {
                                    log.error("Digital Earth Africa visual asset download failed: {} — {}",
                                            result.statusCode(), body);
                                    return new RuntimeException("Digital Earth Africa visual asset download failed: "
                                            + body);
                                }))
                        .bodyToFlux(DataBuffer.class)
                        .doOnNext(buffer -> writeBuffer(output, buffer))
                        .then()
                        .block();
            }
            return renderCroppedJpeg(downloadPath, transform);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to download or render the Digital Earth Africa visual asset", ex);
        } finally {
            if (cogFile != null) {
                try {
                    Files.deleteIfExists(cogFile);
                } catch (IOException ex) {
                    log.warn("Unable to remove temporary Digital Earth Africa asset {}", cogFile, ex);
                }
            }
        }
    }

    private void writeBuffer(OutputStream output, DataBuffer buffer) {
        try {
            byte[] bytes = new byte[buffer.readableByteCount()];
            buffer.read(bytes);
            output.write(bytes);
        } catch (IOException ex) {
            throw new RuntimeException("Unable to save the Digital Earth Africa visual asset", ex);
        } finally {
            DataBufferUtils.release(buffer);
        }
    }

    private byte[] renderCroppedJpeg(Path cogeFile, JsonNode transform) throws IOException {
        double pixelX = transform.get(0).asDouble();
        double rotationX = transform.get(1).asDouble();
        double originX = transform.get(2).asDouble();
        double rotationY = transform.get(3).asDouble();
        double pixelY = transform.get(4).asDouble();
        double originY = transform.get(5).asDouble();

        List<double[]> projectedCorners = List.of(
                wgs84ToUtm35South(BBOX_MIN_LON, BBOX_MIN_LAT),
                wgs84ToUtm35South(BBOX_MIN_LON, BBOX_MAX_LAT),
                wgs84ToUtm35South(BBOX_MAX_LON, BBOX_MIN_LAT),
                wgs84ToUtm35South(BBOX_MAX_LON, BBOX_MAX_LAT));

        double minPixelX = Double.POSITIVE_INFINITY;
        double maxPixelX = Double.NEGATIVE_INFINITY;
        double minPixelY = Double.POSITIVE_INFINITY;
        double maxPixelY = Double.NEGATIVE_INFINITY;
        double determinant = pixelX * pixelY - rotationX * rotationY;
        if (Math.abs(determinant) < 1e-12) {
            throw new IOException("Digital Earth Africa visual asset has an invalid georeferencing transform");
        }

        for (double[] corner : projectedCorners) {
            double dx = corner[0] - originX;
            double dy = corner[1] - originY;
            double x = (dx * pixelY - rotationX * dy) / determinant;
            double y = (pixelX * dy - dx * rotationY) / determinant;
            minPixelX = Math.min(minPixelX, x);
            maxPixelX = Math.max(maxPixelX, x);
            minPixelY = Math.min(minPixelY, y);
            maxPixelY = Math.max(maxPixelY, y);
        }

        try (ImageInputStream input = ImageIO.createImageInputStream(cogeFile.toFile())) {
            if (input == null) {
                throw new IOException("Unable to open the downloaded Digital Earth Africa GeoTIFF");
            }
            Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new IOException("No Java ImageIO reader can decode the Digital Earth Africa GeoTIFF");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(input, true, true);
                int imageWidth = reader.getWidth(0);
                int imageHeight = reader.getHeight(0);
                int x = Math.max(0, (int) Math.floor(minPixelX));
                int y = Math.max(0, (int) Math.floor(minPixelY));
                int right = Math.min(imageWidth, (int) Math.ceil(maxPixelX));
                int bottom = Math.min(imageHeight, (int) Math.ceil(maxPixelY));
                if (right <= x || bottom <= y) {
                    throw new IOException("The Digital Earth Africa scene does not overlap the dam bounding box");
                }

                Rectangle region = new Rectangle(x, y, right - x, bottom - y);
                int subsampling = Math.max(1, (int) Math.ceil(Math.max(
                        (double) region.width / MAX_PREVIEW_WIDTH,
                        (double) region.height / MAX_PREVIEW_HEIGHT)));
                ImageReadParam readParam = reader.getDefaultReadParam();
                readParam.setSourceRegion(region);
                readParam.setSourceSubsampling(subsampling, subsampling, 0, 0);

                BufferedImage source = reader.read(0, readParam);
                BufferedImage rgb = new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = rgb.createGraphics();
                try {
                    graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    graphics.drawImage(source, new AffineTransform(), null);
                } finally {
                    graphics.dispose();
                }

                try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                    if (!ImageIO.write(rgb, "jpg", output)) {
                        throw new IOException("No Java ImageIO JPEG writer is available");
                    }
                    return output.toByteArray();
                }
            } finally {
                reader.dispose();
            }
        }
    }

    private double[] wgs84ToUtm35South(double longitude, double latitude) {
        double semiMajorAxis = 6_378_137.0;
        double eccentricitySquared = 0.00669437999014;
        double secondEccentricitySquared = eccentricitySquared / (1.0 - eccentricitySquared);
        double scale = 0.9996;
        double latitudeRadians = Math.toRadians(latitude);
        double longitudeRadians = Math.toRadians(longitude);
        double centralMeridian = Math.toRadians(27.0);
        double sinLatitude = Math.sin(latitudeRadians);
        double cosLatitude = Math.cos(latitudeRadians);
        double tanLatitude = Math.tan(latitudeRadians);
        double n = semiMajorAxis / Math.sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude);
        double t = tanLatitude * tanLatitude;
        double c = secondEccentricitySquared * cosLatitude * cosLatitude;
        double a = cosLatitude * (longitudeRadians - centralMeridian);
        double e4 = eccentricitySquared * eccentricitySquared;
        double e6 = e4 * eccentricitySquared;
        double meridionalArc = semiMajorAxis * (
                (1 - eccentricitySquared / 4 - 3 * e4 / 64 - 5 * e6 / 256) * latitudeRadians
                        - (3 * eccentricitySquared / 8 + 3 * e4 / 32 + 45 * e6 / 1024)
                                * Math.sin(2 * latitudeRadians)
                        + (15 * e4 / 256 + 45 * e6 / 1024) * Math.sin(4 * latitudeRadians)
                        - (35 * e6 / 3072) * Math.sin(6 * latitudeRadians));
        double easting = 500_000 + scale * n * (
                a + (1 - t + c) * Math.pow(a, 3) / 6
                        + (5 - 18 * t + t * t + 72 * c - 58 * secondEccentricitySquared)
                                * Math.pow(a, 5) / 120);
        double northing = scale * (
                meridionalArc + n * tanLatitude * (
                        a * a / 2
                                + (5 - t + 9 * c + 4 * c * c) * Math.pow(a, 4) / 24
                                + (61 - 58 * t + t * t + 600 * c - 330 * secondEccentricitySquared)
                                        * Math.pow(a, 6) / 720))
                + 10_000_000;
        return new double[] { easting, northing };
    }
}
