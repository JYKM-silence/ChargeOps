package com.chargeops.utils;

/**
 * 文件存储接口
 *
 * <p><b>为什么要抽这个接口：</b>
 * 培训项目的代码通常是 Controller 直接 {@code @Autowired AliOssUtil}，
 * 也就是「上传」这件事被写死成「传到阿里云 OSS」。
 * 后果是本地开发必须先去阿里云开一个 bucket、配好 AK/SK 才能上传一张图 ——
 * 一个刚克隆下来的项目，图片上传功能直接是坏的。
 *
 * <p>抽成接口之后，上传动作和存储介质解耦：Controller 只认识
 * {@code upload(bytes, objectName)}，不关心文件最后落在磁盘上还是对象存储上。
 * 换存储只是换一个实现类，Controller 一行都不用改 —— 这就是依赖倒置原则
 * 在「只有一个调用方」时依然值得用的原因：它省掉的是**换实现时的改动面**，
 * 而不是调用方数量。
 *
 * <p><b>现有实现：只有一个。</b>
 * <ul>
 *   <li>{@link LocalFileStorage} —— 写本地磁盘，配 {@code chargeops.storage.type=local}</li>
 * </ul>
 *
 * <p>★ 老库那个 {@code AliOssUtil}（传阿里云 OSS）**没有拷过来**，因为拷贝它意味着
 * 同时拷进来一个「没有 AK/SK 就完全跑不通」的功能，而这个项目从头到尾没有真实的
 * 云账号。它现在的状态是「接口留着、实现按需再加」——
 * 等到真有账号时，写一个 {@code OssFileStorage implements FileStorage}、
 * 在 {@link com.chargeops.config.StorageConfiguration} 的 switch 里加一个分支就行，
 * <b>调用方一行都不用改</b>。这才是上面说的依赖倒置真正兑现的时刻。
 *
 * <p>这也是为什么 {@code type} 配了一个不认识的值会启动失败而不是静默降级：
 * 现在只有 local 一个分支，写着 {@code type=oss} 却悄悄退回本地磁盘的话，
 * 会变成「以为上传到了云上，其实写在本机临时目录里」——
 * 而容器一重启文件就没了，且没有任何人事先知道。
 *
 * <p><b>为什么不声明 checked exception：</b>
 * 调用方是 Controller，它无论如何都要把失败翻译成 {@code Result.error(...)} 返回，
 * 强行要求它写 try/catch 只会催生一堆空 catch —— 而空 catch 会把
 * 「磁盘满了」和「目录没权限」这两类完全不同的故障变成同一个「上传失败」。
 * 实现类内部转成运行时异常，由 CommonController 统一兜住并记日志。
 */
public interface FileStorage {

    /**
     * 保存一个文件，返回可被浏览器 / 小程序直接访问的 URL
     *
     * @param bytes      文件内容
     * @param objectName 对象名（文件名）。调用方负责保证全局唯一，
     *                   现在传的是 UUID + 后缀
     * @return 完整访问 URL，会被直接存进数据库
     */
    String upload(byte[] bytes, String objectName);
}
