package com.qiniu.android.storage;

import com.qiniu.android.common.Zone;
import com.qiniu.android.http.Client;
import com.qiniu.android.http.ResponseInfo;
import com.qiniu.android.utils.AsyncRun;

import org.json.JSONObject;

import java.io.File;

/**
 * 七牛文件上传管理器
 * 一般默认可以使用这个类的方法来上传数据和文件。会自动检测文件的大小，
 * 只要超过了{@link Configuration#putThreshold} 异步方法会使用分片方片上传。
 * 同步上传方法使用表单方式上传，建议只对小文件使用同步方式
 */
public final class UploadManager {
    private final Configuration config;
    private final Client client;

    public UploadManager() {
        this(new Configuration.Builder().build());
    }

    public UploadManager(Configuration config) {
        this.config = config;
        this.client = new Client(config.proxy, config.connectTimeout, config.responseTimeout,
                config.urlConverter, config.dns);
    }

    public UploadManager(Recorder recorder, KeyGenerator keyGen) {
        this(new Configuration.Builder().recorder(recorder, keyGen).build());
    }

    public UploadManager(Recorder recorder) {
        this(recorder, null);
    }

    private static boolean areInvalidArg(final String key, byte[] data, File f,
                                         String token, final UpCompletionHandler completionHandler) {
        if (completionHandler == null) {
            throw new IllegalArgumentException("no UpCompletionHandler");
        }
        String message = null;
        if (f == null && data == null) {
            message = "no input data";
        } else if (token == null || token.equals("")) {
            message = "no token";
        }

        ResponseInfo info = null;
        if (message != null) {
            info = ResponseInfo.invalidArgument(message);
        }
        if ((f != null && f.length() == 0) || (data != null && data.length == 0)) {
            info = ResponseInfo.zeroSize();
        }
        if (info != null) {
            final ResponseInfo info2 = info;
            AsyncRun.runInMain(new Runnable() {
                @Override
                public void run() {
                    completionHandler.complete(key, info2, null);
                }
            });
            return true;
        }

        return false;
    }

    /**
     * 上传数据
     *
     * @param data     上传的数据
     * @param key      上传数据保存的文件名
     * @param token    上传凭证
     * @param complete 上传完成后续处理动作
     * @param options  上传数据的可选参数
     */
    public void put(final byte[] data, final String key, final String token,
                    final UpCompletionHandler complete, final UploadOptions options) {
        if (areInvalidArg(key, data, null, token, complete)) {
            return;
        }

        final UpCompletionHandler completionHandler = new UpCompletionHandler() {
            @Override
            public void complete(final String key, final ResponseInfo info, final JSONObject response) {
                AsyncRun.runInMain(new Runnable() {
                    @Override
                    public void run() {
                        complete.complete(key, info, response);
                    }
                });
            }
        };

        final UpToken decodedToken = UpToken.parse(token);
        if (decodedToken == null) {
            final ResponseInfo info = ResponseInfo.invalidToken("invalid token");
            completionHandler.complete(key, info, null);
            return;
        }

        Zone z = config.zone;
        z.preQuery(token, new Zone.QueryHandler() {
            @Override
            public void onSuccess() {
                AsyncRun.runInMain(new Runnable() {
                    @Override
                    public void run() {
                        FormUploader.upload(client, config, data, key, decodedToken, completionHandler, options);
                    }
                });
            }

            @Override
            public void onFailure(int reason) {
                final ResponseInfo info = ResponseInfo.invalidToken("invalid token");
                completionHandler.complete(key, info, null);
            }
        });

    }

    /**
     * 上传文件
     *
     * @param filePath          上传的文件路径
     * @param key               上传文件保存的文件名
     * @param token             上传凭证
     * @param completionHandler 上传完成的后续处理动作
     * @param options           上传数据的可选参数
     */
    public void put(String filePath, String key, String token, UpCompletionHandler completionHandler,
                    final UploadOptions options) {
        put(new File(filePath), key, token, completionHandler, options);
    }

    /**
     * 上传文件
     *
     * @param file     上传的文件对象
     * @param key      上传文件保存的文件名
     * @param token    上传凭证
     * @param complete 上传完成的后续处理动作
     * @param options  上传数据的可选参数
     */
    public void put(final File file, final String key, String token, final UpCompletionHandler complete,
                    final UploadOptions options) {
        if (areInvalidArg(key, null, file, token, complete)) {
            return;
        }
        final UpCompletionHandler completionHandler = new UpCompletionHandler() {
            @Override
            public void complete(final String key, final ResponseInfo info, final JSONObject response) {
                AsyncRun.runInMain(new Runnable() {
                    @Override
                    public void run() {
                        complete.complete(key, info, response);
                    }
                });
            }
        };

        final UpToken decodedToken = UpToken.parse(token);
        if (decodedToken == null) {
            final ResponseInfo info = ResponseInfo.invalidToken("invalid token");
            completionHandler.complete(key, info, null);
            return;
        }

        Zone z = config.zone;
        z.preQuery(token, new Zone.QueryHandler() {
            @Override
            public void onSuccess() {
                long size = file.length();
                if (size <= config.putThreshold) {
                    FormUploader.upload(client, config, file, key, decodedToken, completionHandler, options);
                    return;
                }
                String recorderKey = config.keyGen.gen(key, file);
                ResumeUploader uploader = new ResumeUploader(client, config, file, key,
                        decodedToken, completionHandler, options, recorderKey);

                AsyncRun.runInMain(uploader);
            }

            @Override
            public void onFailure(int reason) {
                final ResponseInfo info = ResponseInfo.invalidToken("invalid token");
                completionHandler.complete(key, info, null);
            }
        });

    }

    /**
     * 同步上传文件。使用 form 表单方式上传，建议只在数据较小情况下使用此方式，如 file.size() < 1024 * 1024。
     *
     * @param data     上传的数据
     * @param key      上传数据保存的文件名
     * @param token    上传凭证
     * @param options  上传数据的可选参数
     *
     * @return 响应信息 ResponseInfo#response 响应体，序列化后 json 格式
     */
    public ResponseInfo syncPut(byte[] data, String key, String token, UploadOptions options) {
        final UpToken decodedToken = UpToken.parse(token);
        ResponseInfo info = areInvalidArg(key, data, null, token, decodedToken);
        if (info != null) {
            return info;
        }
        return FormUploader.syncUpload(client, config, data, key, decodedToken, options);
    }

    /**
     * 同步上传文件。使用 form 表单方式上传，建议只在文件较小情况下使用此方式，如 file.size() < 1024 * 1024。
     *
     * @param file     上传的文件对象
     * @param key      上传数据保存的文件名
     * @param token    上传凭证
     * @param options  上传数据的可选参数
     *
     * @return 响应信息 ResponseInfo#response 响应体，序列化后 json 格式
     */
    public ResponseInfo syncPut(File file, String key, String token, UploadOptions options) {
        final UpToken decodedToken = UpToken.parse(token);
        ResponseInfo info = areInvalidArg(key, null, file, token, decodedToken);
        if (info != null) {
            return info;
        }
        return FormUploader.syncUpload(client, config, file, key, decodedToken, options);
    }

    /**
     * 同步上传文件。使用 form 表单方式上传，建议只在文件较小情况下使用此方式，如 file.size() < 1024 * 1024。
     *
     * @param file     上传的文件绝对路径
     * @param key      上传数据保存的文件名
     * @param token    上传凭证
     * @param options  上传数据的可选参数
     *
     * @return 响应信息 ResponseInfo#response 响应体，序列化后 json 格式
     */
    public ResponseInfo syncPut(String file, String key, String token, UploadOptions options) {
        return syncPut(new File(file), key, token, options);
    }

    private static ResponseInfo areInvalidArg(final String key, byte[] data, File f, String token,
                                              UpToken decodedToken) {
        String message = null;
        if (f == null && data == null) {
            message = "no input data";
        } else if (token == null || token.equals("")) {
            message = "no token";
        }
        ResponseInfo info = null;
        if (decodedToken == null) {
            info = ResponseInfo.invalidToken("invalid token");
        }
        if (message != null) {
            info = ResponseInfo.invalidArgument(message);
        }
        if ((f != null && f.length() == 0) || (data != null && data.length == 0)) {
            info = ResponseInfo.zeroSize();
        }
        return info;
    }

}
