package com.munggle.post.service;

import com.munggle.domain.exception.UserNotFoundException;
import com.munggle.domain.model.entity.*;
import com.munggle.image.dto.FileInfoDto;
import com.munggle.image.service.FileS3UploadService;
import com.munggle.post.dto.PostCreateDto;
import com.munggle.post.dto.PostDetailResponseDto;
import com.munggle.post.dto.PostUpdateDto;
import com.munggle.post.mapper.PostMapper;
import com.munggle.post.repository.*;
import com.munggle.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;

import static com.munggle.domain.exception.ExceptionMessage.USER_NOT_FOUND;

@Service
@RequiredArgsConstructor
public class PostServiceImpl implements PostService {

    private final FileS3UploadService fileS3UploadService;
    private final PostRepository postRepository;
    private final UserRepository userRepository;
    private final PostImageRepository postImageRepository;
    private final TagRepository tagRepository;
    private final PostTagRepository postTagRepository;
    private final CuratingService curatingService;

    /**
     * 게시글 상세보기 메소드
     *
     * @param postId
     * @param userId
     * @return
     */
    @Override
    public PostDetailResponseDto getDetailPost(Long postId, Long userId) {
        Post post = postRepository.findByIdAndIsDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchElementException());
        String nickname = post.getUser().getNickname();
        Boolean isMine = post.getUser().getId().equals(userId);

        // 해당 post의 이미지 url만 list로 가져오기
        List<String> imageUrls = new ArrayList<>();
        List<PostImage> postImages = postImageRepository.findAllByPost(post);
        for (PostImage postImage : postImages) {
            imageUrls.add(postImage.getImageURL());
        }

        // 해당 post의 해시태그 명만 list로 가져오기
        List<String> hashtags = new ArrayList<>();
        List<PostTag> postTags = post.getPostTagList();
        for (PostTag postTag : postTags) {
            if (postTag.getIsDeleted() == false) {
                hashtags.add(postTag.getTag().getTagNm());
                curatingService.saveRecentTag(userId, postTag.getTag().getId()); // 큐레이팅을 위한 해시태그 수집
            }
        }

        return PostMapper.toPostDetailResponseDto(post, nickname, isMine, imageUrls, hashtags);
    }

    /**
     * 게시글 생성 메소드
     *
     * @param postCreateDto
     */
    @Override
    @Transactional
    public void insertPost(PostCreateDto postCreateDto) {

        Post newPost = PostMapper.toEntity(postCreateDto);
        Long userId = postCreateDto.getUserId();
        newPost.addUserToPost(userRepository.findByIdAndIsEnabledTrue(userId)
                .orElseThrow(() -> new UserNotFoundException(USER_NOT_FOUND)));

        // 게시글 영속화
        Long postId = postRepository.save(newPost).getId();

        // 이미지 저장
        if (postCreateDto.getImages() != null) {
            List<MultipartFile> files = postCreateDto.getImages();
            String uploadPath = userId + "/" + postId + "/";
            List<FileInfoDto> fileInfoDtos = fileS3UploadService.uploadFlieList(uploadPath, files); //s3 저장소에 업로드

            for (FileInfoDto fileInfo : fileInfoDtos) { // db에 이미지 파일 정보 저장
                PostImage newImage = PostMapper.toPostImageEntity(fileInfo, newPost);
                postImageRepository.save(newImage);
            }
        }

        // 해시태그 저장
        List<String> hashtags = postCreateDto.getHashtags();
        List<PostTag> postTags = new ArrayList<>();
        for (String hashtag : hashtags) {
            Tag newTag = tagRepository.findByTagNm(hashtag)
                    .orElseGet(() -> tagRepository.save(PostMapper.toTagEntity(hashtag)));

            PostTagId newPostTagId = PostMapper.toPostTagIdEntity(postId, newTag.getId());
            PostTag newPostTag = PostMapper.toPostTagEntity(newPostTagId, newPost, newTag);
            newPostTag.markAsDeletedFalse();
            postTags.add(newPostTag);

            curatingService.saveRecentTag(userId, newTag.getId()); // 큐레이팅을 위한 해시태그 수집
        }

        postTagRepository.saveAll(postTags); // 영속화
    }

    /**
     * 게시글 수정 메소드
     *
     * 수정 가능 필드: title / content / isPrivate / image / hashtag
     * @param postUpdateDto
     */
    @Override
    @Transactional
    public void updatePost(PostUpdateDto postUpdateDto) {

        String newTitle = postUpdateDto.getPostTitle();
        String newContent = postUpdateDto.getPostContent();
        Boolean newIsPrivate = postUpdateDto.getIsPrivate();

        Post updatePost = postRepository.findById(postUpdateDto.getPostId())
                .orElseThrow();

        updatePost.updatePost(newTitle, newContent, newIsPrivate);

        // 기존 post image 삭제
        Long userId = updatePost.getUser().getId();
        String uploadPath = userId + "/" + updatePost.getId() + "/";
        fileS3UploadService.removeFolderFiles(uploadPath); // s3 저장소에 올라간 기존 파일 삭제

        postImageRepository.deleteByPostId(updatePost.getId()); // db에서 데이터 삭제

        // 업데이트 된 post image 등록
        if (postUpdateDto.getImages() != null) {
            List<MultipartFile> files = postUpdateDto.getImages();
            List<FileInfoDto> fileInfoDtos = fileS3UploadService.uploadFlieList(uploadPath, files); //s3 저장소에 업로드

            for (FileInfoDto fileInfo : fileInfoDtos) { // db에 이미지 파일 정보 저장
                PostImage newImage = PostMapper.toPostImageEntity(fileInfo, updatePost);
                postImageRepository.save(newImage);
            }
        }

        // 기존 해시태그 삭제
        List<PostTag> deleteTags = postTagRepository.findAllByPost(updatePost);  // db에서 isDeleted true로 변경
        for (PostTag deleteTag : deleteTags) {
            deleteTag.markAsDeleted();
        }

        // 업데이트 된 해시태그 저장
        List<String> hashtags = postUpdateDto.getHashtags();
        List<PostTag> postTags = new ArrayList<>();
        for (String hashtag : hashtags) {
            Tag tag = tagRepository.findByTagNm(hashtag)
                    .orElseGet(() -> tagRepository.save(PostMapper.toTagEntity(hashtag)));

            PostTagId findId = PostMapper.toPostTagIdEntity(updatePost.getId(), tag.getId());
            PostTag updatePostTag = postTagRepository.findById(findId)
                    .orElse(PostMapper.toPostTagEntity(findId, updatePost, tag));
            updatePostTag.markAsDeletedFalse(); // isDeleted false로 변경
            postTags.add(updatePostTag);
        }

        postTagRepository.saveAll(postTags); // 영속화

    }

    /**
     * 게시글 삭제 메소드
     *
     * @param postId
     */
    @Override
    @Transactional
    public void deletePost(Long postId) {
        Post post = postRepository.findByIdAndIsDeletedFalse(postId)
                .orElseThrow(() -> new NoSuchElementException());
        post.markAsDeleted();

        // post image 삭제
        List<PostImage> deleteImages = postImageRepository.findAllByPost(post);  // db에서 isDeleted true로 변경
        for (PostImage deleteImage : deleteImages) {
            deleteImage.markAsDeleted();
        }

        // post tag 삭제
        List<PostTag> deleteTags = postTagRepository.findAllByPost(post);  // db에서 isDeleted true로 변경
        for (PostTag deleteTag : deleteTags) {
            deleteTag.markAsDeleted();
        }
    }

}
