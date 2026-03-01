package com.example.popping.service;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.multipart.MultipartFile;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.example.popping.domain.Image;
import com.example.popping.domain.ImageStatus;
import com.example.popping.domain.Post;
import com.example.popping.dto.ImageResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.ImageRepository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ImageServiceTest {

    @Mock AmazonS3 amazonS3;
    @Mock ImageRepository imageRepository;

    @InjectMocks ImageService imageService;

    private final String bucketName = "test-bucket";

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(imageService, "bucketName", bucketName);
    }

    @Test
    @DisplayName("uploadAndCreateTempImage: 업로드 후 TEMP 이미지 저장 + ImageResponse 반환")
    void uploadAndCreateTempImage_success() throws Exception {
        // given
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "cat.jpg",
                "image/jpeg",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        when(amazonS3.getUrl(eq(bucketName), anyString()))
                .thenReturn(new URL("https://s3.amazonaws.com/test-bucket/a1b2c3d4e5_cat.jpg"));

        // when
        ImageResponse res = imageService.uploadAndCreateTempImage(file);

        // then
        verify(amazonS3).putObject(any(PutObjectRequest.class));

        ArgumentCaptor<Image> captor = ArgumentCaptor.forClass(Image.class);
        verify(imageRepository).save(captor.capture());

        Image saved = captor.getValue();
        assertEquals("https://s3.amazonaws.com/test-bucket/a1b2c3d4e5_cat.jpg", saved.getImageUrl());

        assertEquals("https://s3.amazonaws.com/test-bucket/a1b2c3d4e5_cat.jpg", res.imageUrl());
    }

    @Test
    @DisplayName("uploadToS3: file이 null이면 EMPTY_IMAGE_FILE 예외")
    void uploadToS3_fail_nullFile() {
        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(null));

        // then
        assertEquals(ErrorType.EMPTY_IMAGE_FILE, ex.getErrorType());
        verifyNoInteractions(amazonS3);
    }

    @Test
    @DisplayName("uploadToS3: 빈 파일이면 EMPTY_IMAGE_FILE 예외")
    void uploadToS3_fail_emptyFile() {
        // given
        MultipartFile empty = mock(MultipartFile.class);
        when(empty.isEmpty()).thenReturn(true);

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(empty));

        // then
        assertEquals(ErrorType.EMPTY_IMAGE_FILE, ex.getErrorType());
        verifyNoInteractions(amazonS3);
    }

    @Test
    @DisplayName("uploadToS3: originalFilename이 null이면 EMPTY_IMAGE_FILE 예외")
    void uploadToS3_fail_nullOriginalFilename() {
        // given
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn(null);

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(file));

        // then
        assertEquals(ErrorType.EMPTY_IMAGE_FILE, ex.getErrorType());
        verifyNoInteractions(amazonS3);
    }

    @Test
    @DisplayName("uploadToS3: 확장자 없으면 NO_FILE_EXTENSION 예외")
    void uploadToS3_fail_noExtension() {
        // given
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("noext");

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(file));

        // then
        assertEquals(ErrorType.NO_FILE_EXTENSION, ex.getErrorType());
        verifyNoInteractions(amazonS3);
    }

    @Test
    @DisplayName("uploadToS3: 허용되지 않은 확장자면 INVALID_FILE_EXTENSION 예외")
    void uploadToS3_fail_invalidExtension() {
        // given
        MultipartFile file = mock(MultipartFile.class);
        when(file.isEmpty()).thenReturn(false);
        when(file.getOriginalFilename()).thenReturn("evil.exe");

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(file));

        // then
        assertEquals(ErrorType.INVALID_FILE_EXTENSION, ex.getErrorType());
        verifyNoInteractions(amazonS3);
    }

    @Test
    @DisplayName("uploadToS3: putObject 중 RuntimeException 발생 시 PUT_OBJECT_EXCEPTION 예외")
    void uploadToS3_fail_putObjectException() throws Exception {
        // given
        MockMultipartFile file = new MockMultipartFile(
                "image",
                "cat.png",
                "image/png",
                "hello".getBytes(StandardCharsets.UTF_8)
        );

        doThrow(new RuntimeException("S3 down"))
                .when(amazonS3).putObject(any(PutObjectRequest.class));

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.uploadToS3(file));

        // then
        assertEquals(ErrorType.PUT_OBJECT_EXCEPTION, ex.getErrorType());
    }

    @Test
    @DisplayName("deleteImages: post 연결 이미지가 있으면 S3 삭제 후 deleteAll")
    void deleteImages_success() {
        // given
        Post post = mock(Post.class);

        Image img1 = mock(Image.class);
        Image img2 = mock(Image.class);

        when(img1.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/a.jpg");
        when(img2.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/b.jpg");

        when(imageRepository.findAllByPost(post)).thenReturn(List.of(img1, img2));

        // when
        imageService.deleteImages(post);

        // then
        verify(amazonS3, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(imageRepository).deleteAll(List.of(img1, img2));
    }

    @Test
    @DisplayName("deleteImages: 연결 이미지가 없으면 아무 작업도 하지 않는다")
    void deleteImages_noImages() {
        // given
        Post post = mock(Post.class);
        when(imageRepository.findAllByPost(post)).thenReturn(List.of());

        // when
        imageService.deleteImages(post);

        // then
        verifyNoInteractions(amazonS3);
        verify(imageRepository, never()).deleteAll(anyList());
    }

    @Test
    @DisplayName("deleteFromS3: URL에서 key 추출 후 deleteObject 호출")
    void deleteFromS3_success_keyDecode() {
        // given
        String imageUrl = "https://test-bucket.s3.amazonaws.com/folder/my%20file.jpg";

        // when
        imageService.deleteFromS3(imageUrl);

        // then
        ArgumentCaptor<DeleteObjectRequest> captor = ArgumentCaptor.forClass(DeleteObjectRequest.class);
        verify(amazonS3).deleteObject(captor.capture());

        DeleteObjectRequest req = captor.getValue();
        assertEquals(bucketName, req.getBucketName());
        assertEquals("folder/my file.jpg", req.getKey());
    }

    @Test
    @DisplayName("deleteFromS3: 잘못된 URL이면 IO_EXCEPTION_ON_IMAGE_DELETE 예외")
    void deleteFromS3_fail_malformedUrl() {
        // given
        String bad = "not-a-url";

        // when
        CustomAppException ex = assertThrows(CustomAppException.class,
                () -> imageService.deleteFromS3(bad));

        // then
        assertEquals(ErrorType.IO_EXCEPTION_ON_IMAGE_DELETE, ex.getErrorType());
    }

    @Test
    @DisplayName("extractImageUrls: img[src]의 src만 추출하며 중복/빈값을 제거한다")
    void extractImageUrls_success_distinctAndFilter() {
        // given
        String html = """
                <div>
                  <img src="https://a.com/1.jpg"/>
                  <img src=""/>
                  <img/>
                  <img src="https://a.com/1.jpg"/>
                  <img src="https://b.com/2.png"/>
                </div>
                """;

        // when
        List<String> urls = imageService.extractImageUrls(html);

        // then
        assertEquals(List.of("https://a.com/1.jpg", "https://b.com/2.png"), urls);
    }

    @Test
    @DisplayName("extractImageUrls: null/blank면 빈 리스트 반환")
    void extractImageUrls_blank() {
        assertEquals(List.of(), imageService.extractImageUrls(null));
        assertEquals(List.of(), imageService.extractImageUrls(" "));
    }

    @Test
    @DisplayName("linkToPostAndMakePermanent: content의 이미지 url로 조회된 Image들에 attachTo(post) 호출")
    void linkToPostAndMakePermanent_success() {
        // given
        Post post = mock(Post.class);
        String html = """
                <div>
                  <img src="https://a.com/1.jpg"/>
                  <img src="https://b.com/2.png"/>
                </div>
                """;

        Image i1 = mock(Image.class);
        Image i2 = mock(Image.class);

        when(imageRepository.findAllByImageUrlIn(List.of("https://a.com/1.jpg", "https://b.com/2.png")))
                .thenReturn(List.of(i1, i2));

        // when
        imageService.linkToPostAndMakePermanent(html, post);

        // then
        verify(i1).attachTo(post);
        verify(i2).attachTo(post);
    }

    @Test
    @DisplayName("linkToPostAndMakePermanent: content에서 이미지가 없으면 repository 조회/attach를 하지 않는다")
    void linkToPostAndMakePermanent_noImages() {
        // given
        Post post = mock(Post.class);
        String html = "<div><p>no img</p></div>";

        // when
        imageService.linkToPostAndMakePermanent(html, post);

        // then
        verify(imageRepository, never()).findAllByImageUrlIn(anyList());
    }

    @Test
    @DisplayName("cleanUpUnusedTempImages: TEMP 이미지를 S3에서 삭제하고 DB에서 삭제한다")
    void cleanUpUnusedTempImages_success() {
        // given
        Image t1 = mock(Image.class);
        Image t2 = mock(Image.class);
        when(t1.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/t1.jpg");
        when(t2.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/t2.jpg");

        when(imageRepository.findAllByStatus(ImageStatus.TEMP)).thenReturn(List.of(t1, t2));

        // when
        assertDoesNotThrow(() -> imageService.cleanUpUnusedTempImages());

        // then
        verify(amazonS3, times(2)).deleteObject(any(DeleteObjectRequest.class));
        verify(imageRepository).delete(t1);
        verify(imageRepository).delete(t2);
    }

    @Test
    @DisplayName("cleanUpUnusedTempImages: 한 건 삭제 실패여도 예외를 던지지 않고 다음 건을 계속 처리한다")
    void cleanUpUnusedTempImages_continueOnCustomAppException() {
        // given
        Image t1 = mock(Image.class);
        Image t2 = mock(Image.class);

        when(t1.getId()).thenReturn(1L);

        when(t1.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/t1.jpg");
        when(t2.getImageUrl()).thenReturn("https://s3.amazonaws.com/test-bucket/t2.jpg");

        when(imageRepository.findAllByStatus(ImageStatus.TEMP)).thenReturn(List.of(t1, t2));

        // 첫 번째 deleteObject만 실패, 두 번째는 성공
        doThrow(new RuntimeException("S3 delete fail"))
                .doNothing()
                .when(amazonS3).deleteObject(any(DeleteObjectRequest.class));

        // when / then
        assertDoesNotThrow(() -> imageService.cleanUpUnusedTempImages());

        // t1: S3 삭제 실패 → DB delete 호출 X
        verify(imageRepository, never()).delete(t1);

        // t2: 계속 진행 → DB delete 호출 O
        verify(imageRepository).delete(t2);

        verify(amazonS3, times(2)).deleteObject(any(DeleteObjectRequest.class));
    }
}
