package com.example.popping.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.*;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
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
import com.amazonaws.util.IOUtils;
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

    private final AmazonS3 amazonS3;
    private final ImageRepository imageRepository;

    @Value("${cloud.aws.s3.bucketName}")
    private String bucketName;

    @Transactional
    public ImageResponse uploadAndCreateTempImage(MultipartFile multipartFile) {
        Image image = Image.builder()
                .imageUrl(upload(multipartFile))
                .build();

        imageRepository.save(image);

        return ImageResponse.from(image.getImageUrl());
    }

    public String upload(MultipartFile image) {
        if (image.isEmpty() || Objects.isNull(image.getOriginalFilename())) {
            throw new CustomAppException(ErrorType.EMPTY_IMAGE_FILE);
        }
        return this.uploadImage(image);
    }

    private String uploadImage(MultipartFile image) {
        this.validateImageFileExtention(image.getOriginalFilename());
        try {
            return this.uploadImageToS3(image);
        } catch (IOException e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_UPLOAD);
        }
    }

    private void validateImageFileExtention(String filename) {
        int lastDotIndex = filename.lastIndexOf(".");
        if (lastDotIndex == -1) {
            throw new CustomAppException(ErrorType.NO_FILE_EXTENSION,
                    "파일 확장자가 없습니다. 올바른 이미지 파일을 업로드해주세요.");
        }

        String extention = filename.substring(lastDotIndex + 1).toLowerCase();
        List<String> allowedExtentionList = Arrays.asList("jpg", "jpeg", "png", "gif");

        if (!allowedExtentionList.contains(extention)) {
            throw new CustomAppException(ErrorType.INVALID_FILE_EXTENSION,
                    "지원하는 이미지 파일 확장자는 " + String.join(", ", allowedExtentionList) + "입니다.");
        }
    }

    private String uploadImageToS3(MultipartFile image) throws IOException {
        String originalFilename = image.getOriginalFilename();
        String extention = originalFilename.substring(originalFilename.lastIndexOf("."));

        String s3FileName = UUID.randomUUID().toString().substring(0, 10) + originalFilename;

        InputStream is = image.getInputStream();
        byte[] bytes = IOUtils.toByteArray(is);

        ObjectMetadata metadata = new ObjectMetadata();
        metadata.setContentType("image/" + extention);
        metadata.setContentLength(bytes.length);
        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(bytes);

        try {
            PutObjectRequest putObjectRequest =
                    new PutObjectRequest(bucketName, s3FileName, byteArrayInputStream, metadata)
                            .withCannedAcl(CannedAccessControlList.PublicRead);
            amazonS3.putObject(putObjectRequest);
        } catch (Exception e) {
            throw new CustomAppException(ErrorType.PUT_OBJECT_EXCEPTION,
                    "S3에 이미지를 업로드하는 중 문제가 발생했습니다: " + originalFilename);
        } finally {
            byteArrayInputStream.close();
            is.close();
        }

        return amazonS3.getUrl(bucketName, s3FileName).toString();
    }

    @Transactional
    public void deleteImages(Post post) {
        List<Image> images = imageRepository.findAllByPost(post);
        if (!images.isEmpty()) {
            images.forEach(image -> deleteImageFromS3(image.getImageUrl()));
            imageRepository.deleteAll(images);
        }
    }

    public void deleteImageFromS3(String imageAddress) {
        String key = getKeyFromImageAddress(imageAddress);
        try {
            amazonS3.deleteObject(new DeleteObjectRequest(bucketName, key));
        } catch (Exception e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_DELETE,
                    "이미지 삭제 중 문제가 발생했습니다: " + imageAddress);
        }
    }

    private String getKeyFromImageAddress(String imageAddress) {
        try {
            URL url = new URL(imageAddress);
            String decodingKey = URLDecoder.decode(url.getPath(), "UTF-8");
            return decodingKey.substring(1);
        } catch (MalformedURLException | UnsupportedEncodingException e) {
            throw new CustomAppException(ErrorType.IO_EXCEPTION_ON_IMAGE_DELETE,
                    "이미지 주소가 잘못되었습니다: " + imageAddress);
        }
    }

    public void linkToPostAndMakePermanent(String content, Post post) {
        List<String> imageUrls = extractImageUrls(content);

        List<Image> images = imageRepository.findAllByImageUrlIn(imageUrls);

        for (Image image : images) {
            image.updatePostAndStatus(post);
        }
    }

    public List<String> extractImageUrls(String htmlContent) {
        List<String> imageUrls = new ArrayList<>();
        Document doc = Jsoup.parse(htmlContent);

        Elements imgTags = doc.select("img");
        for (Element img : imgTags) {
            String src = img.attr("src");
            imageUrls.add(src);
        }
        return imageUrls;
    }

    public void cleanUpUnusedTempImages() {
        List<Image> tempImages = imageRepository.findAllByStatus(ImageStatus.TEMP);
        for (Image image : tempImages) {
            try {
                deleteImageFromS3(image.getImageUrl());
                imageRepository.delete(image);
            } catch (Exception e) {
                throw new CustomAppException(ErrorType.TEMP_IMAGE_DELETE_EXCEPTION,
                        "임시 이미지 삭제 중 문제가 발생했습니다: " + image.getImageUrl());
            }
        }
    }
}
