/*
 * Copyright (c) 2021 xjunz. 保留所有权利
 */
package xjunz.tool.werecord.ui.message.fragment.dialog;

import android.content.ContentValues;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.databinding.DataBindingUtil;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import xjunz.tool.werecord.R;
import xjunz.tool.werecord.databinding.ItemMessageViewerBinding;
import xjunz.tool.werecord.impl.model.message.Message;
import xjunz.tool.werecord.impl.repo.MessageRepository;
import xjunz.tool.werecord.impl.repo.RepositoryFactory;
import xjunz.tool.werecord.ui.customview.MasterToast;
import xjunz.tool.werecord.util.Utils;

public class MessageViewerDialog extends DialogFragment {
    private Message mVictim;

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setStyle(STYLE_NO_TITLE, R.style.Base_Dialog_Translucent_NoDim);
    }

    public MessageViewerDialog setMessage(Message msg) {
        this.mVictim = msg;
        return this;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.dialog_message_viewer, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        RecyclerView rvViewer = view.findViewById(R.id.rv_message_viewer);
        rvViewer.setAdapter(new MessageViewAdapter());
    }

    private class MessageViewAdapter extends RecyclerView.Adapter<MessageViewerViewHolder> {
        private final List<String> keyList;
        private final ContentValues values;
        private final MessageRepository repository;

        MessageViewAdapter() {
            values = mVictim.getValues();
            keyList = new ArrayList<>(values.keySet());
            repository = RepositoryFactory.get(MessageRepository.class);
        }

        @NonNull
        @Override
        public MessageViewerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new MessageViewerViewHolder(DataBindingUtil.inflate(getLayoutInflater(), R.layout.item_message_viewer, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull MessageViewerViewHolder holder, int position) {
            String key = keyList.get(position);
            holder.key = key;
            holder.binding.setKey(key);
            Object value = values.get(key);
            if (value instanceof byte[]) {
                holder.content = new String((byte[]) value);
            } else {
                holder.content = value == null ? null : value.toString();
            }
            holder.binding.setValue(holder.content);
            holder.binding.setTag(repository.getType(key));
            holder.binding.executePendingBindings();
        }

        @Override
        public int getItemCount() {
            return values.size();
        }
    }

    private class MessageViewerViewHolder extends RecyclerView.ViewHolder {
        private final ItemMessageViewerBinding binding;
        private String key;
        private String content;

        public MessageViewerViewHolder(@NonNull ItemMessageViewerBinding binding) {
            super(binding.getRoot());
            this.binding = binding;
            //长按复制属性标题/内容
            binding.getRoot().setOnLongClickListener(v -> {
                if (key == null) {
                    return true;
                }
                showCopyDialog(key, content);
                return true;
            });
        }
    }

    /**
     * 长按属性项时弹出复制选项：复制标题、复制内容、复制“标题: 内容”
     */
    private void showCopyDialog(@NonNull String key, @Nullable String value) {
        final String caption = key;
        final String content = value == null ? "" : value;
        new AlertDialog.Builder(requireContext())
                .setTitle(caption)
                .setItems(new String[]{
                        getString(R.string.copy_title),
                        getString(R.string.copy_content),
                        getString(R.string.copy_title_and_content)
                }, (dialog, which) -> {
                    switch (which) {
                        case 0:
                            Utils.copyPlainText(caption, caption);
                            break;
                        case 1:
                            Utils.copyPlainText(caption, content);
                            break;
                        default:
                            Utils.copyPlainText(caption, caption + ": " + content);
                            break;
                    }
                    MasterToast.shortToast(R.string.has_copied_to_clipboard);
                })
                .show();
    }
}
