package ucll.be.dammonitorbackend.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.buffer.DefaultDataBufferFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeFunction;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

class SatelliteServiceTest {

    @Test
    void fetchSatelliteImageUsesLatestSuitableSceneAndRendersVisualCog() throws IOException {
        byte[] tiff = createTiff();
        AtomicReference<URI> downloadedAsset = new AtomicReference<>();
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger assetRequests = new AtomicInteger();
        AtomicReference<String> responseBody = new AtomicReference<>(
                """
                {
                  "features": [
                    %s,
                    %s
                  ]
                }
                """.formatted(feature("2026-10-02T08:17:26Z", 1.0), feature("2026-10-04T08:17:38Z", 19.0)));

        SatelliteService service = new SatelliteService(
                mockWebClient(responseBody, tiff, downloadedAsset, searchRequests, assetRequests),
                "https://explorer.digitalearth.africa/stac/search");

        SatelliteService.SatelliteImageResult result = service.fetchSatelliteImage();

        assertEquals(Instant.parse("2026-10-04T08:17:38Z"), result.dateTaken());
        assertEquals("Digital Earth Africa Sentinel-2 L2A", result.collection().displayName());
        assertEquals(
                "https://deafrica-sentinel-2.s3.af-south-1.amazonaws.com/test/visual.tif",
                result.assetUrl());
        assertEquals(result.assetUrl(), downloadedAsset.get().toString());
        assertArrayEquals(new byte[] { (byte) 0xff, (byte) 0xd8 }, new byte[] {
                result.imageBytes()[0], result.imageBytes()[1] });
        assertSame(result, service.fetchSatelliteImage());
        assertEquals(1, searchRequests.get());
        assertEquals(1, assetRequests.get());

        SatelliteService.SatelliteImageResult refreshed = service.fetchSatelliteImage(true);

        assertNotSame(result, refreshed);
        assertEquals(2, searchRequests.get());
        assertEquals(2, assetRequests.get());
    }

    @Test
    void fetchSatelliteImageReportsWhenNoSuitableSceneExists() {
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger assetRequests = new AtomicInteger();
        SatelliteService service = new SatelliteService(
                mockWebClient(new AtomicReference<>("{\"features\":[]}"), new byte[0], new AtomicReference<>(),
                        searchRequests, assetRequests),
                "https://explorer.digitalearth.africa/stac/search");

        SatelliteService.SatelliteImageUnavailableException error = assertThrows(
                SatelliteService.SatelliteImageUnavailableException.class,
                service::fetchSatelliteImage);

        assertTrue(error.getMessage().contains("No suitable Digital Earth Africa Sentinel-2 scene"));
        assertEquals(0, assetRequests.get());
    }

    @Test
    void failedRefreshDoesNotLeaveAStaleImageInCache() throws IOException {
        AtomicReference<String> searchResponse = new AtomicReference<>("""
                {"features":[%s]}
                """.formatted(feature("2026-10-04T08:17:38Z", 10.0)));
        AtomicInteger searchRequests = new AtomicInteger();
        AtomicInteger assetRequests = new AtomicInteger();
        SatelliteService service = new SatelliteService(
                mockWebClient(searchResponse, createTiff(), new AtomicReference<>(), searchRequests, assetRequests),
                "https://explorer.digitalearth.africa/stac/search");

        service.fetchSatelliteImage();
        searchResponse.set("{\"features\":[]}");

        assertThrows(SatelliteService.SatelliteImageUnavailableException.class,
                () -> service.fetchSatelliteImage(true));
        assertThrows(SatelliteService.SatelliteImageUnavailableException.class, service::fetchSatelliteImage);
        assertEquals(3, searchRequests.get());
        assertEquals(1, assetRequests.get());
    }

    private WebClient mockWebClient(
            AtomicReference<String> searchResponse,
            byte[] tiff,
            AtomicReference<URI> downloadedAsset,
            AtomicInteger searchRequests,
            AtomicInteger assetRequests) {
        ExchangeFunction exchange = request -> {
            if (request.method() == HttpMethod.POST) {
                searchRequests.incrementAndGet();
                return Mono.just(ClientResponse.create(HttpStatus.OK)
                        .header("Content-Type", "application/json")
                        .body(searchResponse.get())
                        .build());
            }
            assetRequests.incrementAndGet();
            downloadedAsset.set(request.url());
            return Mono.just(ClientResponse.create(HttpStatus.OK)
                    .header("Content-Type", "image/tiff")
                    .body(Flux.just(DefaultDataBufferFactory.sharedInstance.wrap(tiff)))
                    .build());
        };
        return WebClient.builder().exchangeFunction(exchange).build();
    }

    private String feature(String datetime, double cloudCover) {
        return """
                {
                  "bbox": [27.7, -25.8, 28.0, -25.7],
                  "geometry": {
                    "type": "Polygon",
                    "coordinates": [[
                      [27.7, -25.8], [28.0, -25.8], [28.0, -25.7],
                      [27.7, -25.7], [27.7, -25.8]
                    ]]
                  },
                  "properties": {
                    "datetime": "%s",
                    "eo:cloud_cover": %s
                  },
                  "assets": {
                    "visual": {
                      "href": "s3://deafrica-sentinel-2/test/visual.tif",
                      "proj:transform": [1000, 0, 500000, 0, -1000, 7200040]
                    }
                  }
                }
                """.formatted(datetime, cloudCover);
    }

    private byte[] createTiff() throws IOException {
        BufferedImage image = new BufferedImage(1000, 1000, BufferedImage.TYPE_3BYTE_BGR);
        image.setRGB(500, 500, 0x336699);
        try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            assertTrue(ImageIO.write(image, "tif", output));
            return output.toByteArray();
        }
    }
}
