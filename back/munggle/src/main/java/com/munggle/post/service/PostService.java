package com.munggle.post.service;

import com.munggle.post.dto.PostCreateDto;
import com.munggle.post.dto.PostUpdateDto;
import org.springframework.stereotype.Service;

@Service
public interface PostService {
    void insertPost(PostCreateDto postCreateDto);

    void updatePost(PostUpdateDto postUpdateDto);
}
