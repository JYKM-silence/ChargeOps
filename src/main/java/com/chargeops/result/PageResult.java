package com.chargeops.result;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 封装分页查询结果
 *
 * <p>为什么要有这个类，而不是直接返回 PageHelper 的 {@code Page}：
 * {@code com.github.pagehelper.Page} 继承自 {@code ArrayList}，
 * 除了数据本身还带着 pageNum / pageSize / total / pages 等一堆字段，
 * 直接序列化它返回前端等于把分页插件的内部结构变成了对外契约 ——
 * 哪天换掉 PageHelper，接口返回的 JSON 结构就变了。而且 {@code Page} 的
 * {@code total} 只有查询真实执行后才有值，单测里手写数据构造出来的 Page total 恒为 0。
 *
 * <p>所以这里是显式的一层隔离：Service 层从 Page 里取出 {@code getTotal()} 和
 * {@code getResult()} 组装成 PageResult，对外只暴露这两个字段。
 */
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResult implements Serializable {

    /** 总记录数（不是当前页的记录数） */
    private long total;

    /** 当前页数据集合 */
    private List<?> records;
}
