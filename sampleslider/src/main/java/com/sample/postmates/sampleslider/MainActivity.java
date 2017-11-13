package com.sample.postmates.sampleslider;

import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import com.postmates.widget.centersliderview.CenterSliderView;

public class MainActivity extends AppCompatActivity {

    CenterSliderView sliderView;
    TextView selectedText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        sliderView = findViewById(R.id.center_slider_view);
        sliderView.addOnSliderListener(new CenterSliderView.OnSliderListener() {
            @Override
            public void onValueSelected(int newValue) {
                String stringUnits = sliderView.getCurrentValueStringUnits();
                selectedText.setText("Selected: " + stringUnits);
            }
        });

        selectedText = findViewById(R.id.selected_text);
        resetClicked(null);
    }

    /**
     * Happens when R.id.reset_button is clicked.
     */
    public void resetClicked(View view) {
        CenterSliderView.SliderInfo info =
                new CenterSliderView.SliderInfo.Builder()
                        .setBounds(0, 60)
                        .setIntervalsToEdge(6)
                        .setLargeTickInterval(5)
                        .setStartValue(27)
                        .setValueTextOverride(27, "Predicted")
                        .build();
        sliderView.setSliderInfo(info);
        selectedText.setText("Selected: ");
    }

}
