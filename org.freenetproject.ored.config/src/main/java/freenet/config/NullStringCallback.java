package freenet.config;

public class NullStringCallback extends StringCallback {

	@Override
	public String get() {
		return "";
	}

	@Override
	public void set(String val) throws InvalidConfigValueException {
		// Ignore
	}

}
