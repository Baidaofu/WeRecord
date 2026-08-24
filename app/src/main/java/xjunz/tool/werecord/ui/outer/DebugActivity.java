/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */

package xjunz.tool.werecord.ui.outer;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Process;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.Nullable;

import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.IOException;

import io.reactivex.android.schedulers.AndroidSchedulers;
import io.reactivex.schedulers.Schedulers;
import xjunz.tool.werecord.App;
import xjunz.tool.werecord.R;
import xjunz.tool.werecord.impl.Environment;
import xjunz.tool.werecord.impl.model.account.User;
import xjunz.tool.werecord.impl.model.message.util.TemplateManager;
import xjunz.tool.werecord.ui.base.BaseActivity;
import xjunz.tool.werecord.ui.customview.MasterToast;
import xjunz.tool.werecord.util.IoUtils;
import xjunz.tool.werecord.util.RxJavaUtils;
import xjunz.tool.werecord.util.ShellUtils;
import xjunz.tool.werecord.util.UiUtils;

public class DebugActivity extends BaseActivity {
    private EditText mEtOutput;
    private TextView mTvSelected;
    private TextView mTvDesc;
    private Environment mEnv;
    private DebugFunction mSelectedFunction;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_debug);
        mEnv = Environment.getInstance();
        mEtOutput = findViewById(R.id.et_output);
        mTvSelected = findViewById(R.id.tv_selected);
        mTvDesc = findViewById(R.id.tv_desc);
    }

    /**
     * 调试功能项：名称 + 说明 + 执行体
     */
    private static class DebugFunction {
        final String name;
        final String description;
        final Runnable action;

        DebugFunction(String name, String description, Runnable action) {
            this.name = name;
            this.description = description;
            this.action = action;
        }
    }

    private final DebugFunction[] mFunctions = {
            new DebugFunction("删除备份表", "删除数据库中的消息备份表，释放存储空间。", this::deleteBackupTable),
            new DebugFunction("显示环境信息", "显示当前运行环境、手机硬件与微信版本信息。", this::showEnvInfo),
            new DebugFunction("导出消息数据库", "将当前工作数据库（已解密）复制到外部存储，便于查看与分析。", this::exportDatabase),
            new DebugFunction("导出联系人数据库", "仅导出rcontact联系人表为独立数据库到外部存储（文件更小，便于分析）。", this::exportContactsTable),
            new DebugFunction("导出消息表", "仅导出message消息表为独立数据库到外部存储（文件更小，便于分析）。", this::exportMessageTable),
            new DebugFunction("备份消息数据库", "将微信原始数据库备份到应用私有目录，用于意外时还原。", this::backupMsgDatabase),
            new DebugFunction("还原消息数据库备份", "用备份覆盖还原微信原始数据库，会强制停止微信，操作前请谨慎。", this::restoreMsgDatabaseBackup),
            new DebugFunction("导出模板数据库", "将消息编辑模板数据库导出到外部存储。", this::exportTemplateDb),
            new DebugFunction("删除模板数据库", "删除消息编辑模板数据库，模板功能可能因此异常，下次启动会重建。", this::deleteTemplateDb),
            new DebugFunction("模拟系统回收", "五秒后强制结束本应用进程，模拟系统回收场景。", this::simulateSysRecycle),
            new DebugFunction("模拟捕获异常", "触发一次已捕获的异常，测试错误提示界面。", this::caughtException),
            new DebugFunction("模拟未捕获异常", "触发一次未捕获异常，测试崩溃收集与上报。", this::uncaughtException)
    };

    public void onSelectFunction(View view) {
        String[] names = new String[mFunctions.length];
        for (int i = 0; i < mFunctions.length; i++) {
            names[i] = mFunctions[i].name;
        }
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(R.string.select_function)
                .setItems(names, (dialog, which) -> {
                    mSelectedFunction = mFunctions[which];
                    mTvSelected.setText(mSelectedFunction.name);
                    mTvDesc.setText(mSelectedFunction.description);
                })
                .show();
    }

    public void onExecute(View view) {
        if (mSelectedFunction == null) {
            MasterToast.shortToast(R.string.no_function_selected);
            return;
        }
        //执行前再次确认，说明该功能的作用
        new androidx.appcompat.app.AlertDialog.Builder(this)
                .setTitle(mSelectedFunction.name)
                .setMessage(mSelectedFunction.description + "\n\n" + getString(R.string.confirm_execute_question))
                .setPositiveButton(R.string.confirm, (dialog, which) -> mSelectedFunction.action.run())
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private void showError(@NotNull Throwable e) {
        mEtOutput.setText(IoUtils.readStackTraceFromThrowable(e));
    }

    private void deleteBackupTable() {
        RxJavaUtils.complete(() -> mEnv.modifyDatabase().dropMessageBackupTables()).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
            @Override
            public void onComplete() {
                super.onComplete();
                UiUtils.toast("已删除");
            }

            @Override
            public void onError(@NotNull Throwable e) {
                super.onError(e);
                showError(e);
            }
        });
    }

    private void showEnvInfo() {
        mEtOutput.setText(Environment.getBasicEnvInfo());
    }

    private void exportDatabase() {
        Dialog dialog = UiUtils.createProgress(this, R.string.please_wait);
        dialog.show();
        if (mEnv.initialized() && mEnv.getCurrentUser() != null) {
            String src = mEnv.getCurrentUser().workerDatabaseFilePath;
            String tar = android.os.Environment.getExternalStorageDirectory() + File.separator + mEnv.getCurrentUser().databasePassword + ".db";
            if (new File(src).exists()) {
                RxJavaUtils.complete(() -> ShellUtils.cp(src, tar)).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
                    @Override
                    public void onComplete() {
                        super.onComplete();
                        dialog.dismiss();
                        UiUtils.toast("已导出到" + tar);
                    }

                    @Override
                    public void onError(@NotNull Throwable e) {
                        super.onError(e);
                        dialog.dismiss();
                        showError(e);
                    }
                });
            }
        }
    }

    /**
     * 将指定表导出为独立的未加密数据库到外部存储
     */
    private void exportTableToExternal(String tableName, String fileName) {
        if (!mEnv.initialized() || mEnv.getCurrentUser() == null) {
            MasterToast.shortToast("环境未初始化");
            return;
        }
        //与导出解密数据库一致：需要编辑模式（工作数据库需以读写方式打开）
        if (!App.config().isEditModeEnabled()) {
            MasterToast.shortToast(R.string.edit_mode_not_enabled);
            return;
        }
        Dialog dialog = UiUtils.createProgress(this, R.string.please_wait);
        dialog.show();
        final String[] result = new String[1];
        RxJavaUtils.complete(() -> {
            //先导出到应用私有缓存目录（应用可写），再通过root复制到外部存储
            File tmpFile = new File(getCacheDir(), fileName);
            //noinspection ResultOfMethodCallIgnored
            tmpFile.delete();
            //sqlcipher的ATTACH不会自动创建文件，必须先创建空文件（与DatabaseExporter一致）
            if (!tmpFile.createNewFile()) {
                throw new IOException("无法创建临时文件: " + tmpFile.getAbsolutePath());
            }
            String internal = tmpFile.getAbsolutePath();
            net.sqlcipher.database.SQLiteDatabase src = mEnv.getWorkerDatabase();
            //清理可能遗留的同名连接
            try {
                src.rawExecSQL("DETACH DATABASE export_db");
            } catch (Exception ignored) {
            }
            //使用唯一连接名，避免与遗留连接冲突
            String attachName = "export_db_" + System.currentTimeMillis();
            try {
                //创建未加密的独立数据库并复制指定表
                src.rawExecSQL("ATTACH DATABASE '" + internal + "' AS " + attachName + " KEY ''");
                src.rawExecSQL("CREATE TABLE " + attachName + "." + tableName + " AS SELECT * FROM main." + tableName);
            } finally {
                //无论成功失败都释放连接，避免连接泄漏
                try {
                    src.rawExecSQL("DETACH DATABASE " + attachName);
                } catch (Exception ignored) {
                }
            }
            //root复制到外部存储
            String tar = android.os.Environment.getExternalStorageDirectory() + File.separator + fileName;
            ShellUtils.cp(internal, tar);
            result[0] = tar;
        }).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
            @Override
            public void onComplete() {
                super.onComplete();
                dialog.dismiss();
                UiUtils.toast("已导出到" + result[0]);
            }

            @Override
            public void onError(@NotNull Throwable e) {
                super.onError(e);
                dialog.dismiss();
                showError(e);
            }
        });
    }

    private void exportContactsTable() {
        exportTableToExternal("rcontact", "werecord_contacts.db");
    }

    private void exportMessageTable() {
        exportTableToExternal("message", "werecord_message.db");
    }

    private void simulateSysRecycle() {
        MasterToast.shortToast("五秒后回收App进程");
        RxJavaUtils.complete(() -> {
            Thread.sleep(5000);
            Log.i("werecord", "simulate system recycle pid_" + Process.myPid());
            ShellUtils.sudo("kill -9 " + Process.myPid());
        }).subscribeOn(Schedulers.io()).observeOn(AndroidSchedulers.mainThread())
                .subscribe(new RxJavaUtils.CompletableObservableAdapter() {
                    @Override
                    public void onError(@NotNull Throwable e) {
                        super.onError(e);
                        showError(e);
                    }
                });
    }

    private void exportTemplateDb() {
        Dialog dialog = UiUtils.createProgress(this, R.string.please_wait);
        if (mEnv.initialized()) {
            mEnv.getCurrentUser();
            File src = getDatabasePath(TemplateManager.TEMPLATE_DB_NAME);
            String tar = android.os.Environment.getExternalStorageDirectory() + File.separator + TemplateManager.TEMPLATE_DB_PWD + ".db";
            if (src.exists()) {
                dialog.show();
                RxJavaUtils.complete(() -> ShellUtils.cp(src.getPath(), tar)).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
                    @Override
                    public void onComplete() {
                        super.onComplete();
                        dialog.dismiss();
                        UiUtils.toast("已导出到" + tar);
                    }

                    @Override
                    public void onError(@NotNull Throwable e) {
                        super.onError(e);
                        dialog.dismiss();
                        showError(e);
                    }
                });
            }
        }
    }

    private void deleteTemplateDb() {
        RxJavaUtils.complete(() -> {
            //noinspection ResultOfMethodCallIgnored
            getDatabasePath(TemplateManager.TEMPLATE_DB_NAME).delete();
        }).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
            @Override
            public void onComplete() {
                super.onComplete();
                UiUtils.toast("已删除");
            }

            @Override
            public void onError(@NotNull Throwable e) {
                super.onError(e);
                showError(e);
            }
        });
    }

    private void restoreMsgDatabaseBackup() {
        User currentUser = getEnvironment().getCurrentUser();
        Dialog progress = UiUtils.createProgress(this, R.string.please_wait);
        if (currentUser.backupDatabaseFilePath == null) {
            MasterToast.shortToast("备份不存在");
            return;
        }
        File backup = new File(currentUser.backupDatabaseFilePath);
        if (!backup.exists()) {
            MasterToast.shortToast("备份不存在");
            return;
        }
        progress.show();
        RxJavaUtils.complete(() -> {
            String databaseOriginalPath = currentUser.originalDatabaseFilePath;
            //先强行停止微信，否则可能导致数据库损坏
            ShellUtils.forceStop("com.tencent.mm");
            ShellUtils.cp(currentUser.backupDatabaseFilePath, currentUser.originalDatabaseFilePath);
            //删除原数据库运行时文件
            //如不删除，微信会检测到数据库损坏，并执行数据库修复，修复数据可能导致数据丢失
            ShellUtils.rmIfExists(databaseOriginalPath + "-shm");
            ShellUtils.rmIfExists(databaseOriginalPath + "-wal");
            ShellUtils.rmIfExists(databaseOriginalPath + ".ini");
            ShellUtils.rmIfExists(databaseOriginalPath + ".sm");
        }).subscribe(new RxJavaUtils.CompletableObservableAdapter() {
            @Override
            public void onComplete() {
                super.onComplete();
                progress.dismiss();
                UiUtils.createLaunch(DebugActivity.this).show();
            }

            @Override
            public void onError(@NotNull Throwable e) {
                super.onError(e);
                progress.dismiss();
                showError(e);
            }
        });
    }

    private void backupMsgDatabase() {
        Dialog progress = UiUtils.createProgress(this, R.string.please_wait);
        progress.show();
        RxJavaUtils.complete(() -> getEnvironment().backupOriginDatabaseOf(getEnvironment().getCurrentUser()))
                .subscribe(new RxJavaUtils.CompletableObservableAdapter() {
                    @Override
                    public void onComplete() {
                        super.onComplete();
                        progress.dismiss();
                        MasterToast.shortToast("备份完成");
                    }

                    @Override
                    public void onError(@NotNull Throwable e) {
                        super.onError(e);
                        progress.dismiss();
                        showError(e);
                    }
                });
    }

    private void caughtException() {
        try {
            throw new RuntimeException("An exception thrown in codes explicitly.");
        } catch (Exception e) {
            UiUtils.showError(this, e);
        }
    }

    private void uncaughtException() {
        throw new RuntimeException("An exception thrown in codes explicitly.");
    }
}
