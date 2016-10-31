package io.sweers.catchup.ui.activity;

import android.os.Bundle;
import android.support.annotation.NonNull;
import android.view.ViewGroup;

import com.bluelinelabs.conductor.Conductor;
import com.bluelinelabs.conductor.Controller;
import com.bluelinelabs.conductor.Router;
import com.bluelinelabs.conductor.RouterTransaction;

import java.util.Map;

import javax.inject.Inject;
import javax.inject.Provider;

import butterknife.BindView;
import butterknife.ButterKnife;
import butterknife.Unbinder;
import io.sweers.catchup.R;
import io.sweers.catchup.app.CatchUpApplication;
import io.sweers.catchup.injection.ControllerComponentBuilder;
import io.sweers.catchup.injection.ControllerComponentBuilderHost;
import io.sweers.catchup.ui.ViewContainer;
import io.sweers.catchup.ui.base.BaseActivity;
import io.sweers.catchup.ui.controllers.PagerController;
import io.sweers.catchup.util.customtabs.CustomTabActivityHelper;

public class MainActivity extends BaseActivity implements ControllerComponentBuilderHost {

  @Inject CustomTabActivityHelper customTab;
  @Inject ViewContainer viewContainer;
  @Inject Map<Class<? extends Controller>, Provider<ControllerComponentBuilder>> componentBuilders;

  @BindView(R.id.controller_container) ViewGroup container;

  private Router router;
  private ActivityComponent component;
  private Unbinder unbinder;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    component = createComponent();
    component.inject(this);
    ViewGroup viewGroup = viewContainer.forActivity(this);
    getLayoutInflater().inflate(R.layout.activity_main, viewGroup);

    unbinder = ButterKnife.bind(this);

    router = Conductor.attachRouter(this, container, savedInstanceState);
    if (!router.hasRootController()) {
      router.setRoot(RouterTransaction.with(new PagerController()));
    }
  }

  protected ActivityComponent createComponent() {
    return DaggerActivityComponent.builder()
        .applicationComponent(CatchUpApplication.component())
        .activityModule(new ActivityModule(this))
        .uiModule(new UiModule())
        .build();
  }

  @Override
  protected void onStart() {
    super.onStart();
    customTab.bindCustomTabsService(this);
  }

  @Override
  protected void onStop() {
    customTab.unbindCustomTabsService(this);
    super.onStop();
  }

  @Override
  public void onBackPressed() {
    if (!router.handleBack()) {
      super.onBackPressed();
    }
  }

  @Override
  protected void onDestroy() {
    customTab.setConnectionCallback(null);
    if (unbinder != null) {
      unbinder.unbind();
    }
    super.onDestroy();
  }

  @NonNull
  public ActivityComponent getComponent() {
    return component;
  }

  @Override
  public <ControllerType extends Controller, ComponentBuilderType extends ControllerComponentBuilder> ComponentBuilderType getComponentBuilder(
      Class<? extends ControllerType> key,
      Class<? extends ComponentBuilderType> builderType) {
    return builderType.cast(componentBuilders.get(key).get());
  }
}
