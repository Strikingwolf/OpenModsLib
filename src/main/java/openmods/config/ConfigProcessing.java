package openmods.config;

import java.io.File;
import java.lang.annotation.Annotation;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.Collections;
import java.util.Map;

import net.minecraft.block.Block;
import net.minecraft.item.Item;
import net.minecraft.item.ItemBlock;
import net.minecraft.tileentity.TileEntity;
import net.minecraftforge.common.config.Configuration;
import openmods.Log;
import openmods.config.RegisterBlock.RegisterTileEntity;

import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.HashBasedTable;
import com.google.common.collect.Maps;
import com.google.common.collect.Table;

import cpw.mods.fml.common.registry.GameRegistry;

public class ConfigProcessing {

	public static class ModConfig {
		private final Configuration config;
		public final Class<?> configClass;
		public final File configFile;
		public final String modId;

		private Table<String, String, ConfigPropertyMeta> properties = HashBasedTable.create();

		private ModConfig(String modId, File configFile, Configuration config, Class<?> configClass) {
			this.modId = modId;
			this.configFile = configFile;
			this.config = config;
			this.configClass = configClass;
		}

		void tryProcessConfig(Field field) {
			ConfigPropertyMeta meta = ConfigPropertyMeta.createMetaForField(config, field);
			if (meta != null) {
				meta.updateValueFromConfig(false);
				properties.put(meta.category.toLowerCase(), meta.name.toLowerCase(), meta);
			}
		}

		public void save() {
			if (config.hasChanged()) config.save();
		}

		public Collection<String> getCategories() {
			return Collections.unmodifiableCollection(properties.rowKeySet());
		}

		public Collection<String> getValues(String category) {
			return Collections.unmodifiableCollection(properties.row(category.toLowerCase()).keySet());
		}

		public ConfigPropertyMeta getValue(String category, String name) {
			return properties.get(category.toLowerCase(), name.toLowerCase());
		}
	}

	private static final Map<String, ModConfig> configs = Maps.newHashMap();

	public static Collection<String> getConfigsIds() {
		return Collections.unmodifiableCollection(configs.keySet());
	}

	public static ModConfig getConfig(String modId) {
		return configs.get(modId.toLowerCase());
	}

	public static void processAnnotations(File configFile, String modId, Configuration config, Class<?> klazz) {
		Preconditions.checkState(!configs.containsKey(modId), "Trying to configure mod '%s' twice", modId);
		ModConfig configMeta = new ModConfig(modId, configFile, config, klazz);
		configs.put(modId.toLowerCase(), configMeta);

		for (Field f : klazz.getFields())
			configMeta.tryProcessConfig(f);
	}

	private interface IAnnotationProcessor<I, A extends Annotation> {
		public void process(I entry, A annotation);

		public String getEntryName(A annotation);

		public boolean isEnabled(String name);
	}

	public static <I, A extends Annotation> void processAnnotations(Class<?> config, Class<I> fieldClass, Class<A> annotationClass, FactoryRegistry<I> factory, IAnnotationProcessor<I, A> processor) {
		for (Field f : config.getFields()) {
			if (Modifier.isStatic(f.getModifiers()) && fieldClass.isAssignableFrom(f.getType())) {
				if (f.isAnnotationPresent(IgnoreFeature.class)) continue;
				A annotation = f.getAnnotation(annotationClass);
				if (annotation == null) {
					Log.warn("Field %s has valid type %s for registration, but no annotation %s", f, fieldClass, annotationClass);
					continue;
				}

				String name = processor.getEntryName(annotation);
				if (!processor.isEnabled(name)) {
					Log.info("Item %s (from field %s) is disabled", name, f);
					continue;
				}

				@SuppressWarnings("unchecked")
				Class<? extends I> fieldType = (Class<? extends I>)f.getType();
				I entry = factory.construct(name, fieldType);
				if (entry == null) continue;
				try {
					f.set(null, entry);
				} catch (Exception e) {
					throw Throwables.propagate(e);
				}
				processor.process(entry, annotation);
			}
		}
	}

	private static String dotName(String a, String b) {
		return a + "." + b;
	}

	private static String underscoreName(String a, String b) {
		return a + "_" + b;
	}

	public static void registerItems(Class<?> klazz, final String mod, final FeatureManager features, final FactoryRegistry<Item> factories) {
		processAnnotations(klazz, Item.class, RegisterItem.class, factories, new IAnnotationProcessor<Item, RegisterItem>() {
			@Override
			public void process(Item item, RegisterItem annotation) {
				String name = dotName(mod, annotation.name());
				GameRegistry.registerItem(item, name);

				String unlocalizedName = annotation.unlocalizedName();
				if (!unlocalizedName.equals(RegisterItem.NONE)) {
					if (unlocalizedName.equals(RegisterItem.DEFAULT)) unlocalizedName = name;
					else unlocalizedName = dotName(mod, unlocalizedName);
					item.setUnlocalizedName(unlocalizedName);
				}
			}

			@Override
			public String getEntryName(RegisterItem annotation) {
				return annotation.name();
			}

			@Override
			public boolean isEnabled(String name) {
				return features.isItemEnabled(name);
			}
		});
	}

	public static void registerBlocks(Class<?> klazz, final String mod, final FeatureManager features, final FactoryRegistry<Block> factories) {
		processAnnotations(klazz, Block.class, RegisterBlock.class, factories, new IAnnotationProcessor<Block, RegisterBlock>() {
			@Override
			public void process(Block block, RegisterBlock annotation) {
				final String name = annotation.name();
				final Class<? extends ItemBlock> itemBlock = annotation.itemBlock();
				Class<? extends TileEntity> teClass = annotation.tileEntity();
				if (teClass == TileEntity.class) teClass = null;

				final String blockName = underscoreName(mod, name);

				GameRegistry.registerBlock(block, itemBlock, blockName);

				String unlocalizedName = annotation.unlocalizedName();
				if (!unlocalizedName.equals(RegisterBlock.NONE)) {
					if (unlocalizedName.equals(RegisterBlock.DEFAULT)) unlocalizedName = dotName(mod, name);
					else unlocalizedName = dotName(mod, unlocalizedName);
					block.setBlockName(unlocalizedName);
				}

				if (teClass != null) GameRegistry.registerTileEntity(teClass, blockName);

				if (block instanceof IRegisterableBlock) ((IRegisterableBlock)block).setupBlock(mod, name, teClass, itemBlock);

				for (RegisterTileEntity te : annotation.tileEntities()) {
					final String teName = underscoreName(mod, te.name());
					GameRegistry.registerTileEntity(te.cls(), teName);
				}
			}

			@Override
			public String getEntryName(RegisterBlock annotation) {
				return annotation.name();
			}

			@Override
			public boolean isEnabled(String name) {
				return features.isBlockEnabled(name);
			}
		});
	}
}
