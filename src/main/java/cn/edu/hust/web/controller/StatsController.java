package cn.edu.hust.web.controller;

import cn.edu.hust.web.entity.SessionAggrStat;
import cn.edu.hust.web.entity.Top10Category;
import cn.edu.hust.web.mapper.SessionAggrStatMapper;
import cn.edu.hust.web.mapper.Top10CategoryMapper;
import cn.edu.hust.web.entity.Top10CategorySession;
import cn.edu.hust.web.mapper.Top10CategorySessionMapper;
import cn.edu.hust.web.dto.Top10CategoryDTO;
import java.util.Map;
import java.util.stream.Collectors;
import cn.edu.hust.web.mapper.SessionDetailMapper;
import cn.edu.hust.web.mapper.SessionRandomExtractMapper;
import org.springframework.beans.BeanUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.Data;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    @Autowired
    private SessionAggrStatMapper sessionAggrStatMapper;

    @Autowired
    private Top10CategoryMapper top10CategoryMapper;

    @Autowired
    private Top10CategorySessionMapper top10CategorySessionMapper;

    @Autowired
    private SessionRandomExtractMapper sessionRandomExtractMapper;

    @Autowired
    private SessionDetailMapper sessionDetailMapper;

    @GetMapping("/session-ratio")
    public List<EChartsData> getSessionRatio(@RequestParam Long taskId) 
    {
        SessionAggrStat stat = sessionAggrStatMapper.selectOne(
                new LambdaQueryWrapper<SessionAggrStat>().eq(SessionAggrStat::getTaskId, taskId)
        );

        List<EChartsData> result = new ArrayList<>();
        if (stat != null) {
            result.add(new EChartsData("1s-3s", stat.getVisitLength_1s_3s_ratio()));
            result.add(new EChartsData("4s-6s", stat.getVisitLength_4s_6s_ratio()));
            result.add(new EChartsData("7s-9s", stat.getVisitLength_7s_9s_ratio()));
            result.add(new EChartsData("10s-30s", stat.getVisitLength_10s_30s_ratio()));
            result.add(new EChartsData("30s-60s", stat.getVisitLength_30s_60s_ratio()));
            result.add(new EChartsData("1m-3m", stat.getVisitLength_1m_3m_ratio()));
            result.add(new EChartsData("3m-10m", stat.getVisitLength_3m_10m_ratio()));
            result.add(new EChartsData("10m-30m", stat.getVisitLength_10m_30m_ratio()));
            result.add(new EChartsData("30m", stat.getVisitLength_30m_ratio()));
        }
        
        return result;
    }

    @GetMapping("/step-ratio")
    public List<EChartsData> getStepRatio(@RequestParam Long taskId) {
        SessionAggrStat stat = sessionAggrStatMapper.selectOne(
                new LambdaQueryWrapper<SessionAggrStat>().eq(SessionAggrStat::getTaskId, taskId)
        );

        List<EChartsData> result = new ArrayList<>();
        if (stat != null) {
            result.add(new EChartsData("1-3步", stat.getStepLength_1_3_ratio()));
            result.add(new EChartsData("4-6步", stat.getStepLength_4_6_ratio()));
            result.add(new EChartsData("7-9步", stat.getStepLength_7_9_ratio()));
            result.add(new EChartsData("10-30步", stat.getStepLength_10_30_ratio()));
            result.add(new EChartsData("30-60步", stat.getStepLength_30_60_ratio()));
            result.add(new EChartsData("60步+", stat.getStepLength_60_ratio()));
        }
        return result;
    }

    @GetMapping("/top10")
    public List<Top10Category> getTop10(@RequestParam Long taskId) {
        return top10CategoryMapper.selectList(
                new LambdaQueryWrapper<Top10Category>()
                        .eq(Top10Category::getTaskId, taskId)
                        .orderByDesc(Top10Category::getClickCount) // Assuming sort by click count or typical usage
                        .last("LIMIT 10")
        );
    }

    @GetMapping("/top10-details")
    public List<Top10CategorySession> getTop10Details(@RequestParam Long taskId) {
        return top10CategorySessionMapper.selectList(
                new LambdaQueryWrapper<Top10CategorySession>()
                        .eq(Top10CategorySession::getTaskId, taskId)
                        .orderByDesc(Top10CategorySession::getClickCount)
        );
    }

    @GetMapping("/top10-nested")
    public List<Top10CategoryDTO> getTop10Nested(@RequestParam Long taskId) {
        // 1. Get Top 10 Categories
        List<Top10Category> categories = top10CategoryMapper.selectList(
                new LambdaQueryWrapper<Top10Category>()
                        .eq(Top10Category::getTaskId, taskId)
                        .orderByDesc(Top10Category::getClickCount)
                        .last("LIMIT 10")
        );

        // 2. Get All Top 10 Sessions for this task
        List<Top10CategorySession> allSessions = top10CategorySessionMapper.selectList(
                new LambdaQueryWrapper<Top10CategorySession>()
                        .eq(Top10CategorySession::getTaskId, taskId)
                        .orderByDesc(Top10CategorySession::getClickCount)
        );

        // 3. Group Sessions by CategoryId
        Map<Long, List<Top10CategorySession>> sessionMap = allSessions.stream()
                .collect(Collectors.groupingBy(Top10CategorySession::getCategoryId));

        // 4. Assemble DTOs
        List<Top10CategoryDTO> result = new ArrayList<>();
        for (Top10Category category : categories) {
            Top10CategoryDTO dto = new Top10CategoryDTO();
            BeanUtils.copyProperties(category, dto);
            
            // Set sessions for this category, or empty list if none
            dto.setSessions(sessionMap.getOrDefault(category.getCategoryId(), new ArrayList<>()));
            
            result.add(dto);
        }

        return result;
    }

    @GetMapping("/random-sessions")
    public List<cn.edu.hust.web.entity.SessionRandomExtract> getRandomSessions(@RequestParam Long taskId) {
        return sessionRandomExtractMapper.selectList(
                new LambdaQueryWrapper<cn.edu.hust.web.entity.SessionRandomExtract>()
                        .eq(cn.edu.hust.web.entity.SessionRandomExtract::getTaskId, taskId)
                        .last("LIMIT 10") 
        );
    }

    @GetMapping("/actions")
    public List<cn.edu.hust.web.entity.SessionDetail> getActionStream(@RequestParam Long taskId) {
        return sessionDetailMapper.selectList(
                new LambdaQueryWrapper<cn.edu.hust.web.entity.SessionDetail>()
                        .eq(cn.edu.hust.web.entity.SessionDetail::getTaskId, taskId)
                        .orderByDesc(cn.edu.hust.web.entity.SessionDetail::getActionTime)
                        .last("LIMIT 100")
        );
    }

    @Data
    public static class EChartsData {
        private String name;
        private Double value;

        public EChartsData(String name, Double value) {
            this.name = name;
            this.value = value;
        }
    }
}
