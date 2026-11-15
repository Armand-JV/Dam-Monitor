package ucll.be.dammonitorbackend.controller;

import ucll.be.dammonitorbackend.service.SatelliteService;
import ucll.be.dammonitorbackend.service.SatelliteService.SatelliteImageResult;
import ucll.be.dammonitorbackend.service.SatelliteService.SatelliteImageUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SatelliteController
 *
 * Exposes:
 *
 *   GET /api/satellite?refresh=true (optional)
 *
 * Success (200):
 *   Content-Type: image/jpeg
 *   X-Image-Date: <ISO-8601 exact scene capture time from Digital Earth Africa STAC>
 *   X-Image-Collection: <satellite source>
 *   X-Image-Asset-URL: <HTTPS URL of the source visual COG>
 *   Body: raw JPEG bytes
 *
 * Failure (503 if no suitable scene is available, otherwise 500):
 *   Content-Type: text/plain
 *   Body: error message
 *
 * Usage:
 *   curl -D - http://localhost:8080/api/satellite -o dam.jpg
 *   # -D - prints headers to stdout so you can see X-Image-Date
 */
@RestController
@RequestMapping("/api")
public class SatelliteController {

    private static final Logger log = LoggerFactory.getLogger(SatelliteController.class);

    /**
     * Response header carrying the exact satellite capture datetime.
     * Value is sourced from the selected feature's {@code properties.datetime}
     * in the Digital Earth Africa STAC response — not an estimate.
     * Format: ISO-8601, e.g. {@code 2026-03-18T08:42:11Z}
     */
    private static final String HEADER_IMAGE_DATE = "X-Image-Date";

    /**
     * Response header carrying the satellite collection name used for the image.
     * Format: human-readable label like "Sentinel-2 L2A (10 m)".
     */
    private static final String HEADER_IMAGE_COLLECTION = "X-Image-Collection";
    private static final String HEADER_IMAGE_ASSET_URL = "X-Image-Asset-URL";

    private final SatelliteService satelliteService;

    public SatelliteController(SatelliteService satelliteService) {
        this.satelliteService = satelliteService;
    }

    /**
     * Returns the cached Sentinel-2 preview, or fetches one if the cache is empty.
     * Pass {@code refresh=true} to replace the cached preview from Digital Earth Africa.
     *
     * The exact capture date verified by the Digital Earth Africa STAC API is
     * included in the {@code X-Image-Date} response header so the frontend
     * can display it alongside the image without a separate request.
     */
    @GetMapping(value = "/satellite", produces = MediaType.IMAGE_JPEG_VALUE)
    public ResponseEntity<byte[]> getSatelliteImage(
            @RequestParam(name = "refresh", defaultValue = "false") boolean refresh) {
        log.info("Received GET /api/satellite request (refresh={})", refresh);

        try {
            SatelliteImageResult result = satelliteService.fetchSatelliteImage(refresh);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .contentLength(result.imageBytes().length)
                    .header("Content-Disposition", "inline; filename=\"satellite.jpg\"")
                    // Exact capture datetime from the Digital Earth Africa STAC response
                    .header(HEADER_IMAGE_DATE, result.dateTaken().toString())
                    // Satellite collection and visual COG source used for the preview
                    .header(HEADER_IMAGE_COLLECTION, result.collection().displayName())
                    .header(HEADER_IMAGE_ASSET_URL, result.assetUrl())
                    .body(result.imageBytes());

        } catch (SatelliteImageUnavailableException ex) {
            log.info("No satellite image is currently available: {}", ex.getMessage());
            return ResponseEntity
                    .status(HttpStatus.SERVICE_UNAVAILABLE)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Satellite imagery unavailable: " + ex.getMessage()).getBytes());
        } catch (Exception ex) {
            log.error("Failed to retrieve satellite image", ex);

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .contentType(MediaType.TEXT_PLAIN)
                    .body(("Error fetching satellite image: " + ex.getMessage()).getBytes());
        }
    }
}