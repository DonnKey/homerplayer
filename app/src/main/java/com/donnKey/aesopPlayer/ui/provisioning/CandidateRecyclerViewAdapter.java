package com.donnKey.aesopPlayer.ui.provisioning;

import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.AppCompatCheckBox;
import androidx.recyclerview.widget.RecyclerView;

import com.donnKey.aesopPlayer.R;
import com.donnKey.aesopPlayer.model.ColourScheme;

import java.util.Objects;

public class CandidateRecyclerViewAdapter
        extends RecyclerView.Adapter<CandidateRecyclerViewAdapter.ViewHolder> {

    private final Provisioning provisioning;
    private final CandidateFragment parentFragment;

    CandidateRecyclerViewAdapter(Provisioning provisioning,
                                 CandidateFragment parentFragment) {
        this.provisioning = provisioning;
        this.parentFragment = parentFragment;
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parentFragment.getContext())
                .inflate(R.layout.fragment_candidate, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull final ViewHolder holder, int position) {
        if (provisioning.candidates.size() <= 0) {
            return;
        }

        // This can fire for rebuilt offscreen entries, and it sees "unchecked"
        holder.selected.setOnCheckedChangeListener(null);
        holder.aCandidate = provisioning.candidates.get(position);

        holder.candidateTitle.setText(holder.aCandidate.bookTitle);
        holder.candidateName.setText(holder.aCandidate.newDirName);
        holder.candidateAudio.setText(holder.aCandidate.audioFile);
        holder.view.setBackgroundColor(ColourScheme.get(position).backgroundColour);
        holder.selected.setChecked(holder.aCandidate.isSelected);
        holder.selected.setEnabled(!holder.aCandidate.collides);
        holder.collision.setVisibility(holder.aCandidate.collides?View.VISIBLE:View.GONE);

        holder.selected.setOnCheckedChangeListener((v,b) -> {
            holder.aCandidate.isSelected = b;
            if (!b) {
                MenuItem all = parentFragment.optionsMenu.findItem(R.id.check_all);
                AppCompatCheckBox allCheckBox = (AppCompatCheckBox) all.getActionView();
                allCheckBox.setChecked(false);
            }
            // Not needed on later releases, but 4.4.4 needs it.
            notifyItemChanged(position);
        });

        holder.view.setOnClickListener((v) ->
                Objects.requireNonNull((ProvisioningActivity) parentFragment.getActivity())
                .updateTitle(holder.aCandidate));
    }

    public class ViewHolder extends RecyclerView.ViewHolder {
        final View view;
        final TextView candidateName;
        final TextView candidateTitle;
        final TextView candidateAudio;
        final CheckBox selected;
        final ImageView collision;

        Provisioning.Candidate aCandidate;

        ViewHolder(View view) {
            super(view);
            this.view = view;
            this.candidateName = view.findViewById(R.id.candidate_name);
            this.candidateTitle = view.findViewById(R.id.candidate_title);
            this.candidateAudio = view.findViewById(R.id.candidate_audio);
            this.selected = view.findViewById(R.id.selected);
            this.collision = view.findViewById(R.id.candidate_collision);
        }

        @NonNull
        @Override
        public String toString() {
            return super.toString() + " '" + aCandidate.newDirName + "'";
        }
    }

    @Override
    public int getItemCount() {
        return provisioning.candidates.size();
    }
}