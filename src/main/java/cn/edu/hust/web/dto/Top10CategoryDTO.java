package cn.edu.hust.web.dto;

import cn.edu.hust.web.entity.Top10CategorySession;
import lombok.Data;

import java.util.List;

@Data
public class Top10CategoryDTO {
    private Long categoryId;
    private Long clickCount;
    private Long orderCount;
    private Long payCount;
    
    // Nested session details
    private List<Top10CategorySession> sessions;
}
