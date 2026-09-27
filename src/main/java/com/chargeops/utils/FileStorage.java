package com.chargeops.utils;

/**
 * 文件存储接口
 *
 * <p> 为什么抽这个接口：上传写死成「传到阿里云 OSS」的话，本地开发必须先去开
 * bucket、配 AK/SK 才能传一张图 —— 刚 clone 下来的项目图片上传直接是坏的。
 * 抽成接口后 Controller 只认识 {@code upload(bytes, objectName)}，
 * 换存储只是换一个实现类，调用方一行不用改：省掉的是换实现时的改动面，
 * 而不是调用方数量。
 *
 * <p>现有实现只有 {@link LocalFileStorage}（配 {@code chargeops.storage.type=local}）。
 * 对象存储那版没有拷过来 —— 拷它等于拷进来一个「没有 AK/SK 就完全跑不通」的功能。
 * 真有账号时写一个 {@code OssFileStorage implements FileStorage}、在
 * {@link com.chargeops.config.StorageConfiguration} 的 switch 里加一个分支即可。
 *
 * <p> 这也解释了为什么 {@code type} 配了个不认识的值会启动失败而不是静默降级：
 * 写着 {@code type=oss} 却悄悄退回本地磁盘，会变成「以为上传到云上，
 * 其实写在本机临时目录」—— 容器一重启文件就没了，且没人事先知道。
 *
 * <p>为什么不声明 checked exception：调用方是 Controller，它无论如何都要把失败
 * 翻译成 {@code Result.error(...)}，强行要求 try/catch 只会催生空 catch ——
 * 而空 catch 会把「磁盘满了」和「目录没权限」变成同一个「上传失败」。
 * 实现类内部转成运行时异常，由全局异常处理器兜住并记日志。
 */
public interface FileStorage {

    /**
     * 保存一个文件，返回可被浏览器 / 小程序直接访问的 URL
     *
     * @param bytes      文件内容
     * @param objectName 对象名（文件名）。调用方负责保证全局唯一，
     * 现在传的是 UUID + 后缀
     * @return 完整访问 URL，会被直接存进数据库
     */
    String upload(byte[] bytes, String objectName);
}
