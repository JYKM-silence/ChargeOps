package com.chargeops.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * AI 运维助手的提问
 *
 * <p> {@code @Size} 的上限不是防注入 —— 真正防的是成本：这句话会被送去向量化一次，
 * 再整段进模型上下文。不限长的话贴一篇手册进去，一次问答的耗时和上下文占用就不可控了。
 */
@Data
@Schema(description = "AI 运维助手提问")
public class AiChatDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    @NotBlank(message = "问题不能为空")
    @Size(max = 500, message = "问题不能超过 500 个字符")
    @Schema(description = "运维人员的自然语言提问，例如「1号桩通信中断告警怎么处理」")
    private String question;

    /**
     * 会话 id，用来把多轮对话串起来。
     *
     * <p> 不传就新开一轮，服务端生成后回给前端；之后前端每轮都带同一个值。
     * 没有它的话「那第二点具体怎么做」这种追问会丢掉上下文。
     */
    @Schema(description = "会话 id，首轮不传，之后带上服务端返回的值")
    private String sessionId;
}
