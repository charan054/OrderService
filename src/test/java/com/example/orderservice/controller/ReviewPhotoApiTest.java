package com.example.orderservice.controller;

import com.example.orderservice.client.PhonepeClient;
import com.example.orderservice.client.ProductClient;
import com.example.orderservice.dto.ProductReview;
import com.example.orderservice.dto.ReviewSubmission;
import com.example.orderservice.kafka.OrderKafkaProducer;
import com.example.orderservice.service.CustomerAuthService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Uploading a review photo, serving it, and attaching only photos this service issued to a review. Real H2 + disk. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ReviewPhotoApiTest {
    private static final long OWNER = 9876500121L;
    private static final long OTHER = 9876500122L;
    private static final String VALID_KEY = "test-service-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private CustomerAuthService customerAuthService;

    @MockitoBean
    private ProductClient productClient;
    @MockitoBean
    private PhonepeClient phonepeClient;
    @MockitoBean
    private OrderKafkaProducer orderKafkaProducer;

    private String session(long phno) {
        return customerAuthService.issueSession(phno).token();
    }

    private static byte[] png() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(30, 20, BufferedImage.TYPE_INT_RGB), "png", out);
        return out.toByteArray();
    }

    private MockMultipartFile file(byte[] bytes, String contentType) {
        return new MockMultipartFile("file", "holiday.png", contentType, bytes);
    }

    private String upload(long phno) throws Exception {
        String json = mockMvc.perform(multipart("/cart/reviews/photo").file(file(png(), "image/png"))
                        .param("phno", String.valueOf(phno)).header("X-Customer-Token", session(phno)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").exists())
                .andReturn().getResponse().getContentAsString();
        return json.replaceAll(".*\"url\":\"([^\"]+)\".*", "$1");
    }

    @Test
    void uploadingNeedsYourOwnSession() throws Exception {
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(png(), "image/png")).param("phno", String.valueOf(OWNER)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(png(), "image/png")).param("phno", String.valueOf(OWNER))
                        .header("X-Customer-Token", session(OTHER)))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUploadedPhotoIsServedPubliclyAsAnImageWithNoSniffing() throws Exception {
        String url = upload(OWNER);

        mockMvc.perform(get(URI.create(url).getPath()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
    }

    @Test
    void somethingThatIsNotAPhotoIsRefusedWhateverItIsCalled() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(html, "image/png")).param("phno", String.valueOf(OWNER))
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isBadRequest());
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(new byte[0], "image/png")).param("phno", String.valueOf(OWNER))
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isBadRequest());
        byte[] huge = new byte[3 * 1024 * 1024];
        huge[0] = (byte) 0xFF; huge[1] = (byte) 0xD8; huge[2] = (byte) 0xFF;
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(huge, "image/jpeg")).param("phno", String.valueOf(OWNER))
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aReviewCanCarryAnUploadedPhotoAndItReachesProductService() throws Exception {
        String url = upload(OWNER);
        when(productClient.addReview(anyLong(), any())).thenAnswer(inv -> {
            ReviewSubmission sent = inv.getArgument(1);
            return new ProductReview(1, sent.reviewerName(), sent.reviewerPhno(), sent.rating(), sent.comment(),
                    LocalDateTime.now(), 7L, sent.photoUrl());
        });

        mockMvc.perform(post("/cart/reviews").param("productId", "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerName\":\"Asha\",\"reviewerPhno\":" + OWNER + ",\"rating\":5,\"comment\":\"Lovely\",\"photoUrl\":\"" + url + "\"}")
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").value(url))
                .andExpect(jsonPath("$.reviewerPhno").doesNotExist());

        ArgumentCaptor<ReviewSubmission> sent = ArgumentCaptor.forClass(ReviewSubmission.class);
        verify(productClient).addReview(anyLong(), sent.capture());
        assertEquals(url, sent.getValue().photoUrl());
    }

    @Test
    void aReviewCannotLinkToAPictureHostedElsewhereOrNeverUploaded() throws Exception {
        for (String bad : new String[]{"https://evil.example/pixel.png",
                "http://localhost/review-photos/00000000-0000-0000-0000-000000000000.jpg", "javascript:alert(1)"}) {
            mockMvc.perform(post("/cart/reviews").param("productId", "7").contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reviewerName\":\"Asha\",\"reviewerPhno\":" + OWNER + ",\"rating\":5,\"comment\":\"x\",\"photoUrl\":\"" + bad + "\"}")
                            .header("X-Customer-Token", session(OWNER)))
                    .andExpect(status().isBadRequest());
        }
        verify(productClient, never()).addReview(anyLong(), any());
    }

    @Test
    void aReviewWithoutAPhotoStillWorksAsBefore() throws Exception {
        when(productClient.addReview(anyLong(), any())).thenAnswer(inv -> {
            ReviewSubmission sent = inv.getArgument(1);
            assertNull(sent.photoUrl());
            return new ProductReview(2, sent.reviewerName(), sent.reviewerPhno(), sent.rating(), sent.comment(), LocalDateTime.now());
        });

        mockMvc.perform(post("/cart/reviews").param("productId", "7").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reviewerName\":\"Asha\",\"reviewerPhno\":" + OWNER + ",\"rating\":4,\"comment\":\"fine\"}")
                        .header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.photoUrl").doesNotExist());
    }

    @Test
    void theServiceKeyMayUploadForAnyone() throws Exception {
        mockMvc.perform(multipart("/cart/reviews/photo").file(file(png(), "image/png")).param("phno", String.valueOf(OTHER))
                        .header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk());
    }

    @Test
    void tidyingPhotosIsForTheServiceKeyOnly() throws Exception {
        mockMvc.perform(post("/cart/reviews/photo/tidy")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/cart/reviews/photo/tidy").header("X-Customer-Token", session(OWNER)))
                .andExpect(status().isForbidden());
        verify(productClient, never()).getReviewPhotoUrls(any());
    }

    @Test
    void tidyDefaultsToADryRunAndNeverTouchesAFreshUpload() throws Exception {
        String url = upload(OWNER);
        when(productClient.getReviewPhotoUrls(VALID_KEY)).thenReturn(java.util.List.of());

        mockMvc.perform(post("/cart/reviews/photo/tidy").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(true));
        // Even a real run keeps it: nobody has attached it to a review yet, but it is minutes old. (The shared test
        // folder may also hold older photos from earlier runs, which a real run rightly removes - so only this one is checked.)
        mockMvc.perform(post("/cart/reviews/photo/tidy").param("dryRun", "false").header("X-Service-Key", VALID_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.dryRun").value(false))
                .andExpect(jsonPath("$.tooRecent").value(org.hamcrest.Matchers.greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.files[?(@ == '%s')]".formatted(URI.create(url).getPath().substring("/review-photos/".length()))).isEmpty());

        mockMvc.perform(get(URI.create(url).getPath())).andExpect(status().isOk());
    }
}
