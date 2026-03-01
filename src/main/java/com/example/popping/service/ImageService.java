package com.example.popping.service;

import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.select.Elements;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.model.CannedAccessControlList;
import com.amazonaws.services.s3.model.DeleteObjectRequest;
import com.amazonaws.services.s3.model.ObjectMetadata;
import com.amazonaws.services.s3.model.PutObjectRequest;
import com.example.popping.domain.Image;
import com.example.popping.domain.ImageStatus;
import com.example.popping.domain.Post;
import com.example.popping.dto.ImageResponse;
import com.example.popping.exception.CustomAppException;
import com.example.popping.exception.ErrorType;
import com.example.popping.repository.ImageRepository;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("jpg", "jpeg", "png", "gif");

    private final AmazonS3 amazonS3;
    private final ImageRepository imageRepository;

    @Value("${cloud.aws.s3.bucketName}")
    private String bucketName;

    @Transactional
    public ImageResponse uploadAndCreateTempImage(MultipartFile multipartFile) {
        String imageUrl = uploadToS3(multipartFile);

        Image image = Image.createTemp(imageUrl);
        imageRepository.save(image);

        return new ImageResponse(imageUrl);
    }

    /**
     * MultipartFile -> S3 업로드 후 public URL 반환
     */
    public String uploadToS3(MultipartFile file) {
        validateFile(file);

        String originalFilename = Objects.requireNonNull(file.getOriginalFilename());
        String extension = extractExtension(originalFilename); // "jpg"
        validateExtension(extension);

        String key = generateS3Key(originalFilename);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentLength(file.getSize());
        metadata.setContentType(contentType(extension)); // "image/jpeg"

        try (InputStream inputStream = file.getInputStream()) {
            PutObjectRequest putReq = new PutObjectRequest(bucketName, key, inputStream, metadata)
                    .withCannedAcl(CannedAccessControlList.PublicRead);

            amazonS3.putObject(putReq);
            return amazonS3.getUrl(bucketName, key).toString();

        } catch (IOException e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_UPLOAD);
        } catch (Exception e) {
            throw new CustomAppException(ErrorType.PUT_OBJECT_EXCEPTION,
                    "S3 업로드 중 문제가 발생했습니다: " + originalFilename);
        }
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CustomAppException(ErrorType.EMPTY_IMAGE_FILE);
        }
        if (file.getOriginalFilename() == null) {
            throw new CustomAppException(ErrorType.EMPTY_IMAGE_FILE);
        }
    }

    private String extractExtension(String filename) {
        int lastDot = filename.lastIndexOf('.');
        if (lastDot < 0) {
            throw new CustomAppException(ErrorType.NO_FILE_EXTENSION,
                    "파일 확장자가 없습니다. 올바른 이미지 파일을 업로드해주세요.");
        }
        return filename.substring(lastDot + 1).toLowerCase(); // "jpg"
    }

    private void validateExtension(String extension) {
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new CustomAppException(ErrorType.INVALID_FILE_EXTENSION,
                    "지원하는 이미지 파일 확장자는 " + String.join(", ", ALLOWED_EXTENSIONS) + "입니다.");
        }
    }

    private String contentType(String extension) {
        // jpg는 표준적으로 image/jpeg
        return switch (extension) {
            case "jpg", "jpeg" -> "image/jpeg";
            case "png" -> "image/png";
            case "gif" -> "image/gif";
            default -> "image/" + extension;
        };
    }

    private String generateS3Key(String originalFilename) {
        // UUID 앞 10자 + 원본 파일명 (예: "a1b2c3d4e5_photo.jpg")
        return UUID.randomUUID().toString().substring(0, 10) + "_" + originalFilename;
    }

    @Transactional
    public void deleteImages(Post post) {
        List<Image> images = imageRepository.findAllByPost(post);
        if (images.isEmpty()) return;

        // S3 삭제 후 DB 삭제
        images.forEach(img -> deleteFromS3(img.getImageUrl()));
        imageRepository.deleteAll(images);
    }

    public void deleteFromS3(String imageUrl) {
        String key = keyFromUrl(imageUrl);
        try {
            amazonS3.deleteObject(new DeleteObjectRequest(bucketName, key));
        } catch (Exception e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_DELETE,
                    "이미지 삭제 중 문제가 발생했습니다: " + imageUrl);
        }
    }

    private String keyFromUrl(String imageUrl) {
        try {
            URL url = new URL(imageUrl);
            String decodedPath = URLDecoder.decode(url.getPath(), StandardCharsets.UTF_8);
            return decodedPath.startsWith("/") ? decodedPath.substring(1) : decodedPath;
        } catch (MalformedURLException e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_DELETE,
                    "이미지 주소가 잘못되었습니다: " + imageUrl);
        }
    }

    @Transactional
    public void linkToPostAndMakePermanent(String content, Post post) {
        List<String> imageUrls = extractImageUrls(content);
        if (imageUrls.isEmpty()) return;

        List<Image> images = imageRepository.findAllByImageUrlIn(imageUrls);
        for (Image image : images) {
            image.attachTo(post);
        }
    }

    public List<String> extractImageUrls(String htmlContent) {
        if (htmlContent == null || htmlContent.isBlank()) return List.of();

        Document doc = Jsoup.parse(htmlContent);

        Elements imgTags = doc.select("img[src]");
        return imgTags.stream()
                .map(img -> img.attr("src"))
                .filter(src -> src != null && !src.isBlank())
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 임시 이미지 전체 삭제
     * 한 건 실패로 전체가 실패하지 않도록 개별 처리 + 로그
     */
    @Transactional
    public void cleanUpUnusedTempImages() {
        List<Image> tempImages = imageRepository.findAllByStatus(ImageStatus.TEMP);

        for (Image image : tempImages) {
            try {
                deleteFromS3(image.getImageUrl());
                imageRepository.delete(image);
            } catch (CustomAppException e) {
                // 정책 선택: 여기서 throw하면 "한 장 실패로 전체 실패"
                // 현재는 계속 진행하고 로그 남김
                log.warn("TEMP 이미지 삭제 실패: imageId={}, url={}, errorType={}",
                        image.getId(), image.getImageUrl(), e.getErrorType());
            } catch (Exception e) {
                log.warn("TEMP 이미지 삭제 중 예기치 못한 오류: imageId={}, url={}",
                        image.getId(), image.getImageUrl(), e);
            }
        }
    }
}