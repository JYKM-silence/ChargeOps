package com.chargeops.controller.admin;

import com.chargeops.dto.AiChatDTO;
import com.chargeops.result.Result;
import com.chargeops.service.AiOpsService;
import com.chargeops.service.KnowledgeBaseService;
import com.chargeops.vo.AiChatVO;
import com.chargeops.vo.KnowledgeChunkVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 管理端 - AI 运维助手。路径 {@code /api/admin/**}，由 {@code JwtTokenAdminInterceptor} 保护。
 *
 * <p> 问答接口是同步的，一次请求要等模型多轮往返（带工具调用时至少两轮），
 * 耗时按秒计。读超时配在 {@code spring.http.clients.read-timeout}，
 * 那里给的 60 秒就是「前端多久会看到超时」的上限。
 */
@RestController
@RequestMapping("/api/admin/ai-ops")
@Tag(name = "管理端-AI运维助手")
@Slf4j
public class AiOpsController {

    private final AiOpsService aiOpsService;

    private final KnowledgeBaseService knowledgeBaseService;

    public AiOpsController(AiOpsService aiOpsService, KnowledgeBaseService knowledgeBaseService) {
        this.aiOpsService = aiOpsService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** 返回里带 {@code sessionId} 和这次召回的知识片段，前端要拿它显示「依据」 */
    @PostMapping("/chat")
    @Operation(summary = "向 AI 运维助手提问")
    public Result<AiChatVO> chat(@Valid @RequestBody AiChatDTO aiChatDTO) {
        return Result.success(aiOpsService.chat(aiChatDTO));
    }

    /** 排查用：只检索不调模型，用来分辨「知识库没内容」和「模型答得不好」 */
    @GetMapping("/knowledge/search")
    @Operation(summary = "检索知识库（不调用模型）")
    public Result<List<KnowledgeChunkVO>> searchKnowledge(@RequestParam String query) {
        return Result.success(aiOpsService.searchKnowledge(query));
    }

    /**
     * 重建索引。改完 {@code classpath:knowledge/} 下的 markdown 之后调它，
     * 不用重启；幂等，重复调用不会让片段翻倍。
     */
    @PostMapping("/knowledge/reload")
    @Operation(summary = "重建知识库索引")
    public Result<Integer> reloadKnowledge() {
        return Result.success(knowledgeBaseService.reload());
    }
}
